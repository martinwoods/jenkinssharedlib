def call () {
    def buildHelper = new com.retailinmotion.buildHelper()
    def octopusHelper = new com.retailinmotion.OctopusHelper()
    def versionInfo
    def libraryName
    def filePath
    def nexusUploadUrl
    def os

    pipeline {
        agent {label 'androidsdk'}
        options { skipDefaultCheckout() }
        stages {
            stage('Clean'){
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
                        
                        // get the short and long git hashes
                        gitHashes=buildHelper.getGitHashes()
                        // Use the informational version for the package name, but replace the full git hash with the short version for display purposes
                        packageString=versionInfo.SafeInformationalVersion.toString().replace(gitHashes.full, gitHashes.short);
                        // Update jenkins build name
                        currentBuild.displayName = "#${versionInfo.FullSemVer}"
                        currentBuild.description = "${versionInfo.InformationalVersion}"
                    }
                }
            }
            /*
            SonarQube

            https://docs.sonarqube.org/latest/analysis/scan/sonarscanner-for-gradle/
            
            */
            stage('Build'){
                steps {
                    withSonarQubeEnv('SonarQubeServer') {
                        bat './gradlew.bat cleanBuildCache'
                        bat './gradlew.bat sonarqube assembleRelease'
                    }
                }
            }

            stage('Upload to Nexus'){
                steps{
                    withCredentials([usernamePassword(credentialsId: 'jenkins-nexus.retailinmotion.com-docker', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        script{
                            os = octopusHelper.checkOs()
                            if (os == 'linux' || os == 'macos'){
                                libraryName = sh(returnStdout: true, script: 'basename `git remote get-url origin` .git').trim()
                            }
                            else if (os == 'windows'){
                                libraryName = powershell(returnStdout: true, script: 'Write-Output ((Split-Path (& git remote get-url origin) -Leaf).replace(".git",""))').trim()
                            }
                            libraryName = libraryName.replace('libraryandroid','')
                            echo "Library name: ${libraryName}"
                            filePath = "./${libraryName}/build/outputs/aar/${libraryName}-release.aar"
                            def exists = fileExists filePath
                            if (exists){
                                echo "Build artifact: ${filePath}"
                            }
                            else{
                                error("Could not locate the build output at '${filePath}'")
                            }
                            nexusUploadUrl = "${env.RiMMavenRelease}com/retailinmotion/${libraryName}/${versionInfo.SafeInformationalVersion}/${libraryName}-${versionInfo.SafeInformationalVersion}.aar"
                            echo "Uploading library to Nexus at ${nexusUploadUrl}"
                            def uploadStatus
                            if (os == 'linux' || os == 'macos'){
                                uploadStatus = sh(returnStatus: true, script: "curl.exe -s -S -u $USERNAME:$PASSWORD --upload-file ${filePath} ${nexusUploadUrl}")
                            }
                            else if (os == 'windows'){
                                def psScript = """
                                \$ErrorActionPreference = 'Stop'
                                \$secpasswd = ConvertTo-SecureString '$PASSWORD' -AsPlainText -Force
                                \$mycreds = New-Object System.Management.Automation.PSCredential ('$USERNAME', \$secpasswd)
                                Invoke-RestMethod -Method Put -Uri ${nexusUploadUrl} -InFile ${filePath} -Credential \$myCreds -ContentType "application/java-archive"
                                """
                                //not using curl because it does not reliably return errors when running in PS
                                uploadStatus = powershell(returnStatus: true, script: psScript)
                            }
                            echo "Status: ${uploadStatus}"

                            if (uploadStatus != 0) {
                                error("Could not upload library to Nexus - see above curl error!")
                            }
                            else{
                                echo "Nexus upload successful"
                            }
                        }
                    }
                }
            }

            stage('Clean Workspace'){
                steps {
                    cleanWs()
                }
            }
        }
    }

}