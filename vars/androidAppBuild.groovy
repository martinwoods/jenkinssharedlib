import groovy.json.JsonSlurper

def call (buildParamsJson) {

    def jsonSlurper = new JsonSlurper()
	println "Received build parameters:"
	println buildParamsJson

    def buildParams = jsonSlurper.parseText(buildParamsJson)
    def sonarProjectKeyOverwrite = buildParams.sonarProjectKeyOverwrite
    def sonarProjectNameOverwrite = buildParams.sonarProjectNameOverwrite
    
    def buildHelper = new com.retailinmotion.buildHelper()
    def octopusHelper = new com.retailinmotion.OctopusHelper()
    def versionInfo
    def projectName
    def os
    def originalBranchName
    def sonarProjectKey
    def sonarProjectName

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
                        originalBranchName = "$env.CHANGE_BRANCH" != "null" ? "$env.CHANGE_BRANCH" : "$env.BRANCH_NAME"
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
                        def apkFiles = findFiles(glob: '**/outputs/**/*.apk')
                        echo "APK Files: ${apkFiles}"
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
