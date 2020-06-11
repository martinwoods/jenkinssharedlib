def call (buildParams) {

    def appName = buildParams.appName
    def deployToOctopusSandbox = buildParams.deployToOctopusSandbox.toString().toBoolean()
    def signingKeystore = buildParams.signingKeystore.toLowerCase()
    def sonarProjectKeyOverwrite = buildParams.sonarProjectKeyOverwrite
    def sonarProjectNameOverwrite = buildParams.sonarProjectNameOverwrite

	/*
	Available parameters to be passed from the app Jenkinsfile

	appName - string, used as the project name for pushing a package to Octopus
	deployToOctopusSandbox - string (use true, 1 or y), controls targeting the Octopus Sandbox server for pushing packages
    signingKeystore - string - use "test" or "prod" to select the appropriate keystore
	sonarProjectKeyOverwrite - string, only use if you need to override the default value (the Bitbucket repo name)
	sonarProjectNameOverwrite - string, only use if you need to override the default value (the Bitbucket repo name)
	*/

    def buildHelper = new com.retailinmotion.buildHelper()
    def octopusHelper = new com.retailinmotion.OctopusHelper()
    def versionInfo
    def projectName
    def os
    def originalBranchName
    def sonarProjectKey
    def sonarProjectName
    def apkOutput
    def packageString
    def sandbox = ''
    def safeBranchName
    def networkPublishPath
    def packageZip

    if (deployToOctopusSandbox){
		sandbox = '-sandbox'
	}

    pipeline {
        agent {label 'androidsdk'}
        options {
            skipDefaultCheckout()
            timestamps()
        }
        stages {
            stage('Clean and checkout'){
                steps {
                    cleanWs()
                    checkout scm
                }
            }
            stage('Get Version'){
                steps {
                    script {
                        def gitVersionTool=tool name: 'GitVersion-5', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
                        versionInfo=buildHelper.getGitVersionInfo(gitVersionTool)
                        echo "Version Info:"
                        echo versionInfo.toString()
                        // Update jenkins build name
                        currentBuild.displayName = "#${versionInfo.FullSemVer}"
                        currentBuild.description = "${versionInfo.InformationalVersion}"
                        originalBranchName = "$env.CHANGE_BRANCH" != "null" ? "$env.CHANGE_BRANCH" : "$env.BRANCH_NAME"
                        commitAuthorSlack = buildHelper.getLastCommitAuthor(true).replace("@retailinmotion.com","").toLowerCase()
                    }
                }
            }

            stage('Get repo info'){
                steps{
                    script{
                        os = octopusHelper.checkOs().toLowerCase()
                        if (os == 'linux' || os == 'macos'){
                            projectName = sh(returnStdout: true, script: 'basename `git remote get-url origin` .git').trim()
                        }
                        else if (os == 'windows'){
                            projectName = powershell(returnStdout: true, script: 'Write-Output ((Split-Path (& git remote get-url origin) -Leaf).replace(".git",""))').trim()
                        }
                        echo "Detected library name: ${projectName}"
                        sonarProjectKey = sonarProjectKeyOverwrite ?: projectName
                        sonarProjectName = sonarProjectNameOverwrite ?: projectName
                    }
                }
            }

            stage('Build'){
                steps {
                    script {
                        scannerHome = tool 'SonarScannerRiM'
                        withSonarQubeEnv('SonarQubeServer') {
                            if (os == 'linux' || os == 'macos'){
                                sh './gradlew cleanBuildCache'
                                sh './gradlew jacocoTestReport'
                                //sh "./gradlew createPom -DversionName=${versionInfo.SafeInformationalVersion}"
                                sh """./gradlew --info sonarqube assembleRelease ^
                                        -Dsonar.projectKey=${sonarProjectKey} -Dsonar.branch.name=${originalBranchName} ^
                                        -Dsonar.projectVersion=${versionInfo.FullSemVer} -Dsonar.projectName=${sonarProjectName}
                                    """
                            }
                            else if (os == 'windows'){
                                bat './gradlew.bat cleanBuildCache'
                                bat './gradlew.bat jacocoTestReport'
                                //bat "./gradlew.bat createPom -DversionName=${versionInfo.SafeInformationalVersion}"
                                bat """./gradlew.bat --info sonarqube assembleRelease ^
                                        -Dsonar.projectKey=${sonarProjectKey} -Dsonar.branch.name=${originalBranchName} ^
                                        -Dsonar.projectVersion=${versionInfo.FullSemVer} -Dsonar.projectName=${sonarProjectName}
                                    """
                            }
                        }
                        apkOutput = findFiles(glob: '**/outputs/**/*.apk')[0]
                        echo "APK output: ${apkOutput}"
                    }
                }
            }

            stage('Sign and package'){
                steps{
                    script {
                        // Create the artifacts folder
                        fileOperations([folderCreateOperation('artifacts')])
                        // Prepare filename
                        def S3SafeKeyRegex="[^0-9a-zA-Z\\!\\-_\\.\\*'\\(\\)]+" // based on https://docs.aws.amazon.com/AmazonS3/latest/dev/UsingMetadata.html#object-key-guidelines-safe-characters
                        packageString = "${versionInfo.SafeInformationalVersion}".replaceAll(S3SafeKeyRegex, "-")
                        safeBranchName = "$env.BRANCH_NAME".replaceAll(S3SafeKeyRegex, "-")
                        packageZip = "artifacts/${appName}.${packageString}.zip"
                        // Sign build
                        withCredentials([string(credentialsId: 'android-production-key-password', variable: 'PROD_KEY_PASSWORD'), string(credentialsId: 'android-production-keystore-password', variable: 'PROD_KEYSTORE_PASSWORD'), file(credentialsId: 'android-production-keystore', variable: 'PROD_KEYSTORE_FILE'), file(credentialsId: 'android-test-keystore', variable: 'TEST_KEYSTORE_FILE'), string(credentialsId: 'android-test-key-password', variable: 'TEST_KEY_PASSWORD'), string(credentialsId: 'android-test-keystore-password', variable: 'TEST_KEYSTORE_PASSWORD')]) {
                            // Copy the keystore files to local directory
                            fileOperations([fileRenameOperation(destination: 'test.jks', source: TEST_KEYSTORE_FILE)])
                            fileOperations([fileRenameOperation(destination: 'prod.jks', source: PROD_KEYSTORE_FILE)])
                            // Choose the keystore details to use and sign the APK
                            def keystoreFile
                            def keystorePass
                            def keyPass
                            if (signingKeystore == 'prod'){
                                keystoreFile = 'prod.jks'
                                keystorePass = PROD_KEYSTORE_PASSWORD
                                keyPass = PROD_KEY_PASSWORD
                            }
                            else{
                                keystoreFile = 'test.jks'
                                keystorePass = TEST_KEYSTORE_PASSWORD
                                keyPass = TEST_KEY_PASSWORD
                            }
                            def androidBuildToolsPath
                            if (os == 'linux' || os == 'macos'){
                                // TODO: get the path for apksigner, see below for Windows
                                sh "apksigner sign --ks ${keystoreFile} --ks-pass ${keystorePass} ${apkOutput}"
                            }
                            else if (os == 'windows'){
                                // Get the path for the latest installed version of Build-Tools, then sign using apkSigner
                                // Could also be extracted from the output of 'sdkmanager --list'
                                androidBuildToolsPath = powershell(returnStdout: true, script: '(Get-ChildItem -Path $env:ANDROID_HOME -Directory -Filter "build-tools\\*" | Sort-Object Name -Descending | Select-Object FullName -First 1).FullName')
                                androidBuildToolsPath = androidBuildToolsPath.trim()
                                def apkSignerPath = "${androidBuildToolsPath}\\apksigner.bat"
                                // Sign the APK
                                bat "${apkSignerPath} sign --ks ${keystoreFile} --ks-pass pass:${keystorePass} --key-pass pass:${keyPass} --out artifacts\\${appName}.apk ${apkOutput}"
                            }
                        }

                        // package into a zip file
                        zip archive: false, dir: 'artifacts/', glob: '', zipFile: packageZip
					}
                }
            }
            /*
            * Store the build artifacts in Octopus package repository and deploy the apk for test environment
            */
            stage ('Deploy to Octopus') {
                steps {
                    script {
                        octopusHelper.pushPackage("${env.JENKINS_URL}${sandbox}", packageZip)

                        // Create a release and deploy it to test, octopus sends the slack message to the user
                        octopusHelper.createRelease("${env.JENKINS_URL}${sandbox}", appName, packageString)
                        octopusHelper.deployExisting("${env.JENKINS_URL}${sandbox}", appName, packageString, "Release Test", "--variable=branchName:${safeBranchName} --variable=SlackUsername:${commitAuthorSlack}")                      
                    }
                }
            }

            stage('Clean Workspace'){
                steps {
                    cleanWs notFailBuild: true
                }
            }
        }
    }
}
