def call (buildParams) {

	/*
	Available parameters to be passed from the app Jenkinsfile

	appName - string, used as the project name for pushing a package to Octopus and for Slack build updates
	projectBuildPath - string, used as the file used for building the package
	unitTestPath - string, unit test project path 
	enableValidation - string
	noTestsOnBranches - list of strings, prevents running tests when the build takes place on these branches (usually master)
	slackTokenId - string, the Jenkins credential ID for the channel token that will receive build notifications
	shouldCleanWorkspace - string (use true, 1 or y). Set to false if build artifacts need to be retained in Jenkins.
	*/

	def appName = buildParams.appName.toLowerCase()
	def projectBuildPath = buildParams.projectBuildPath
	def unitTestPath = buildParams.unitTestPath
	def unitTestRootFolder = buildParams.unitTestRootFolder
	def sonarExclusions = buildParams.sonarExclusions
	def sonarCoverageExclusions = buildParams.sonarCoverageExclusions
	def validationImageName = buildParams.validationImageName
	def noTestsOnBranches = buildParams.noTestsOnBranches
	def slackTokenId = buildParams.slackTokenId
	def shouldCleanWorkspace = buildParams.shouldCleanWorkspace != null ? buildParams.shouldCleanWorkspace.toString().toBoolean() : true
	def deployToOctopusSandbox = buildParams.deployToOctopusSandbox.toString().toBoolean()
	def enableValidation = buildParams.enableValidation.toString().toBoolean()

	def buildHelper = new com.retailinmotion.buildHelper()
	def octopusHelper = new com.retailinmotion.OctopusHelper()

	def branchName = "$env.BRANCH_NAME" // Branch name being build (i.e. PR-01)
	def originalBranchName = "$env.CHANGE_BRANCH" != "null" ? "$env.CHANGE_BRANCH" : "$env.BRANCH_NAME" // Original Branch where the changes were made (in the case of a PR it will be the feature branch i.e. feature\SYS-000)
	
	def nugetSource = env.NuGetPullRIM
	def sonarUrl = env.SonarUrl
	def reportPortalUrl = env.ReportPortalUrl
	def commitAuthor
	def commitAuthorSlack
	def versionInfo
	def deployOutput
	def packageString

	def sandbox = ''

	if (deployToOctopusSandbox){
		sandbox = '-sandbox'
	}

	pipeline {
		agent { label 'dotnet6' }
        options {
            skipDefaultCheckout()
            timestamps()
        }
		stages {
			// Clean up the environment and checkout code
			stage("Checkout project") {
				steps {
					cleanWs()
					checkout scm
					echo "Branch name: $branchName"
					echo "Original Branch name: $originalBranchName"
				}
			}

			stage("Gather version info"){
				steps {
					script {
						def gitVersionTool=tool name: 'GitVersion', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
						versionInfo=buildHelper.getGitVersionInfo(gitVersionTool, null, null, env.CHANGE_BRANCH)
						echo "Version Info:"
						echo versionInfo.toString()
						
						gitHashes=buildHelper.getGitHashes()
						packageString=versionInfo.SafeInformationalVersion.toString().replace(gitHashes.full, gitHashes.short);

						currentBuild.displayName = "${versionInfo.FullSemVer}"
						currentBuild.description = "${versionInfo.InformationalVersion}"		

						commitAuthor = buildHelper.getLastCommitAuthor()
						commitAuthorSlack = buildHelper.getLastCommitAuthor(true).replace("@retailinmotion.com","").toLowerCase()
					}
				}
			}

			stage("Run unit tests and get quality metrics") {
				when {
					expression {
						
						if(enableValidation){
							if(unitTestPath == '')
							{
								error("unitTestPath cannot be empty when the validation is enabled.")
							}
							
							return true
						}										

						if(!noTestsOnBranches.contains(branchName)){
							return false
						}										
					}
				}
				steps {
					script {
						scannerHome = tool 'SonarScannerMSBuild'
						javaTool =  tool 'JDK11'
					}

					withEnv(["JAVA_HOME=\"${javaTool}\""]) {
						withSonarQubeEnv('SonarQubeServer') {
							bat "dotnet restore -s ${nugetSource}"
							bat "${scannerHome}\\SonarScanner.MSBuild.exe begin /key:${appName} /version:${versionInfo.FullSemVer} /d:sonar.branch.name=\"${branchName}\"  /d:sonar.exclusions=\"${sonarExclusions}\" /d:sonar.coverage.exclusions=\"${sonarCoverageExclusions}\" /d:sonar.cs.opencover.reportsPaths=\"${unitTestRootFolder}/coverage.opencover.xml\" /d:sonar.cs.vstest.reportsPaths=\"${unitTestRootFolder}/TestResults/TestResults.trx\""
							bat "dotnet build --no-restore"
							bat "dotnet test ${unitTestPath} /p:CollectCoverage=true /p:Exclude=\"[xunit.*]*\" /p:CoverletOutputFormat=\"opencover\" /p:CoverletOutputDirectory=\"./\" --logger \"trx;LogFileName=TestResults.trx\""
							bat "${scannerHome}\\SonarScanner.MSBuild.exe end"
						}
					}
				}
			}

			stage("Build") {
				steps {
					script {
						
						bat "dotnet restore -s ${nugetSource}"
						bat """
							dotnet publish ${projectBuildPath} --no-restore --self-contained -c Release -r win-x64 -p:PublishProfile=FolderProfile -p:PublishDir=../../codebase -p:EnvironmentName="#{EnvironmentName}" -p:AspNetCoreHostingModel="OutOfProcess"
						"""
					}
				}
			}

            stage("Package") {
                steps {
                    script {

						vectorCloudZip="artifacts\\${appName}.${packageString}.zip"
                    }
					zip archive: false, dir: "codebase\\" , glob: '', zipFile: vectorCloudZip
                }
            }

			stage ('Send to Octopus'){
				steps {
					script {
						
						octopusHelper.pushPackage("${env.JENKINS_URL}${sandbox}", vectorCloudZip)
						echo "Creating releases"
						octopusHelper.createReleaseFromFolder("${env.JENKINS_URL}${sandbox}", "${appName}", packageString, "${env.WORKSPACE}\\artifacts")
					}
				}
			}

			stage ('Tag build'){
				agent { label 'linux' }
				when { branch 'master'}
				steps {
					script {
						sshagent(['jenkins-bitbucket.rim.local']) {
							
							checkout scm

							sh script: "git config --global user.name \"jenkins.svn\"", label: "Setting git user.name"
                        	sh script: "git config --global user.email \"devops@retailinmotion.com\"", label: "Setting git user.email"
							sh	"git tag -a ${versionInfo.FullSemVer} -m \"Release ${versionInfo.FullSemVer}\" "
							sh	"git push --tags"
						}
					}
				}
			}

			stage("Cleanup workspace"){
				when {
					expression {
						return shouldCleanWorkspace
					}
				}
				steps {
					cleanWs()
				}
			}
		}
		post {
 			failure {
				script {
					def message = "@${commitAuthorSlack}: ${appName} version ${versionInfo.FullSemVer} build failed for branch: ${branchName}"
					buildHelper.sendNotifications(currentBuild.result, slackTokenId, commitAuthor, message);
				}
			}
			success {
				script {		
					// Only send success notifications for develop and master
					if (branchName in ["develop", "master"]){
						// populate an array of maps to attach to slack notification
						def fields=[]
						def output=[:]
						// parse the output from octopusHelper.deploy
						def parsedOutput=octopusHelper.parseDeployInfo(deployOutput.toString())
						output.title="Deploy Output"
						// get the helm notes field from the JSON data
						output.value=parsedOutput.info.status.notes
						fields.add(output)
						
						buildHelper.sendNotifications(currentBuild.result, slackTokenId, commitAuthor, "${appName} version ${versionInfo.FullSemVer} has been deployed for branch: ${branchName}", buildHelper.generateSlackAttachments(fields))
					}
				}
			}
		}
	}
}