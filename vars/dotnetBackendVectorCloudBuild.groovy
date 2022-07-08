def call (buildParams) {

	/*
	Available parameters to be passed from the app Jenkinsfile

	appName - string, used as the project name for pushing a package to Octopus and for Slack build updates
	projectName - string, used as the file used for building the package

	validationImageName - string
	noTestsOnBranches - list of strings, prevents running tests when the build takes place on these branches (usually master)
	slackTokenId - string, the Jenkins credential ID for the channel token that will receive build notifications
	shouldCleanWorkspace - string (use true, 1 or y). Set to false if build artifacts need to be retained in Jenkins.
	*/

	def appName = buildParams.appName.toLowerCase()
	def projectName = buildParams.projecyName
	def validationImageName = buildParams.validationImageName
	def noTestsOnBranches = buildParams.noTestsOnBranches
	def slackTokenId = buildParams.slackTokenId
	def shouldCleanWorkspace = buildParams.shouldCleanWorkspace != null ? buildParams.shouldCleanWorkspace.toString().toBoolean() : true
	def deployToOctopusSandbox = buildParams.deployToOctopusSandbox.toString().toBoolean()

	def buildHelper = new com.retailinmotion.buildHelper()
	def octopusHelper = new com.retailinmotion.OctopusHelper()

	def branchName = "$env.BRANCH_NAME" // Branch name being build (i.e. PR-01)
	def originalBranchName = "$env.CHANGE_BRANCH" != "null" ? "$env.CHANGE_BRANCH" : "$env.BRANCH_NAME" // Original Branch where the changes were made (in the case of a PR it will be the feature branch i.e. feature\SYS-000)
	def validationFullImageName
		
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
						def gitHashes=buildHelper.getGitHashes()
						def gitVersionTool=tool name: 'GitVersion', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
						versionInfo=buildHelper.getGitVersionInfo(gitVersionTool)

						packageString = versionInfo.SafeInformationalVersion.toString().replace(gitHashes.full, gitHashes.short)
						currentBuild.displayName = "${versionInfo.FullSemVer}"
						commitAuthor = buildHelper.getLastCommitAuthor()
						commitAuthorSlack = buildHelper.getLastCommitAuthor(true).replace("@retailinmotion.com","").toLowerCase()
					}
				}
			}

			stage("Run unit tests and get quality metrics") {
				when {
					expression { 
						return buildParams.validationImageName != '' && !(noTestsOnBranches.contains(branchName))
					}  
				}
				steps {
					script {
						validationFullImageName = "${validationImageName}:${versionInfo.SafeFullSemVer}"
					}
					
					// TODO: include sonnarqube scan once the support for dotnet 6 is available 
					// https://support.retailinmotion.com/browse/DEVOPSREQ-76

					bat """
						dotnet restore -s ${nugetSource} \
						RUN dotnet build --no-restore \
						dotnet test tests/Tests.Unit/Tests.Unit.csproj 
					"""
				}
			}
			stage("Build") {
				
				steps {
					script {
						bat "dir"
						
						bat "dotnet restore -s ${$nugetSource}"
						bat "dotnet publish src/${projectName}/${projectName}.csproj --no-restore --self-contained -c Release -r win-x64 -p:PublishProfile=FolderProfile -p:PublishDir=../../codebase"

						bat "dir"
					}
				}
			}

            stage("Package") {
                steps {
                    script {

						vectorCloudZip="artifacts\\Vector.Cloud.Migration.${packageString}"
                    }
					zip archive: false, dir: "codebase\\" , glob: '', zipFile: vectorCloudZip
                }
            }

			stage ('Tag build'){
				agent { label 'linux' }
				when { branch 'master'}
				steps {
					script {
						sshagent(['jenkins-bitbucket.rim.local']) {
                            sh script: "git config --global user.name \"jenkins.svn\"", label: "Setting git user.name"
                        	sh script: "git config --global user.email \"devops@retailinmotion.com\"", label: "Setting git user.email"
							sh	"git tag -a ${versionInfo.FullSemVer} -m \"Release ${versionInfo.FullSemVer}\" "
							sh	"git push --tags"
						}
					}
				}
			}

			stage ('Send to Octopus'){	
				steps {
					script {
						
						octopusHelper.pushPackage("${env.JENKINS_URL}${sandbox}", vectorCloudZip)
						echo "Creating releases"
						octopusHelper.createRelease("${env.JENKINS_URL}${sandbox}", "${appName}", packageString)
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
					buildHelper.sendNotifications(currentBuild.result, slackTokenId, commitAuthor, "@${commitAuthorSlack}: ${appName} version ${versionInfo.SafeFullSemVer} build failed for branch: ${branchName}")
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
						
						buildHelper.sendNotifications(currentBuild.result, slackTokenId, commitAuthor, "${appName} version ${versionInfo.SafeFullSemVer} has been deployed for branch: ${branchName}", buildHelper.generateSlackAttachments(fields))
					}
				}
			}
		}
	}
}