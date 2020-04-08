def call () {
    def buildHelper = new com.retailinmotion.buildHelper()
    def octopusHelper = new com.retailinmotion.OctopusHelper()
    def versionInfo
    def libraryName
    def filePath
    def nexusUploadUrl
    def os
    def originalBranchName

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
                    }
                }
            }
            stage('Get repo info'){
                steps{
                    script{
                        os = octopusHelper.checkOs().toLowerCase()
                        if (os == 'linux' || os == 'macos'){
                            libraryName = sh(returnStdout: true, script: 'basename `git remote get-url origin` .git').trim()
                        }
                        else if (os == 'windows'){
                            libraryName = powershell(returnStdout: true, script: 'Write-Output ((Split-Path (& git remote get-url origin) -Leaf).replace(".git",""))').trim()
                        }
                        echo "Detected library name: ${libraryName}"
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
                                sh "./gradlew createPom -DversionName=${versionInfo.SafeInformationalVersion}"
                                sh """./gradlew --info sonarqube assembleRelease ^
                                        -Dsonar.projectKey=${libraryName} -Dsonar.branch.name=${originalBranchName} ^
                                        -Dsonar.projectVersion=${versionInfo.FullSemVer} -Dsonar.projectName=${libraryName}
                                    """
                            }
                            else if (os == 'windows'){
                                bat './gradlew.bat cleanBuildCache'
                                bat './gradlew.bat jacocoTestReport'
                                bat "./gradlew.bat createPom -DversionName=${versionInfo.SafeInformationalVersion}"
                                bat """./gradlew.bat --info sonarqube assembleRelease ^
                                        -Dsonar.projectKey=${libraryName} -Dsonar.branch.name=${originalBranchName} ^
                                        -Dsonar.projectVersion=${versionInfo.FullSemVer} -Dsonar.projectName=${libraryName}
                                    """
                            }

                        }
                    }
                }
            }

            stage('Upload to Nexus'){
                steps{
                    withCredentials([usernamePassword(credentialsId: 'jenkins-nexus.retailinmotion.com-docker', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        script{
                            def aarFiles = findFiles(glob: '**/outputs/*/*.aar')
                            echo "AarFiles: ${aarFiles}"
                            aarFilePath = aarFiles[0].path
                            
                            def pomFiles = findFiles(glob: "**/${libraryName}-${versionInfo.SafeInformationalVersion}.xml")
                            echo "pomFiles: ${pomFiles}"
                            pomFilePath = pomFiles[0].path

                            nexusAarUrl = "${env.RiMMavenRelease}com/retailinmotion/${libraryName}/${versionInfo.SafeInformationalVersion}/${libraryName}-${versionInfo.SafeInformationalVersion}.aar"
                            buildHelper.uploadFileToNexus(aarFilePath, 'application/java-archive', nexusAarUrl, "$USERNAME", "$PASSWORD", os)

                            nexusPomUrl = "${env.RiMMavenRelease}com/retailinmotion/${libraryName}/${versionInfo.SafeInformationalVersion}/${libraryName}-${versionInfo.SafeInformationalVersion}.pom"
                            buildHelper.uploadFileToNexus(pomFilePath, 'application/xml', nexusPomUrl, "$USERNAME", "$PASSWORD", os)
                        }
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
