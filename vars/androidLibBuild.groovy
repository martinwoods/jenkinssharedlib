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
            stage('Get library name'){
                steps{
                    script{
                        os = octopusHelper.checkOs()
                        if (os == 'linux' || os == 'macos'){
                            libraryName = sh(returnStdout: true, script: 'basename `git remote get-url origin` .git').trim()
                        }
                        else if (os == 'windows'){
                            libraryName = powershell(returnStdout: true, script: 'Write-Output ((Split-Path (& git remote get-url origin) -Leaf).replace(".git",""))').trim()
                        }
                        echo "Detected library name: ${libraryName}"
                    }
                }
            }
            stage('Build'){
                steps {
                    script {
                        scannerHome = tool 'SonarScannerRiM'
                        sonar.projectKey = libraryName
                    }
                    withSonarQubeEnv('SonarQubeServer') {
                        bat './gradlew.bat cleanBuildCache'
                        bat "./gradlew.bat sonarqube assembleRelease"
                    }
                }
            }

            stage('Upload to Nexus'){
                steps{
                    withCredentials([usernamePassword(credentialsId: 'jenkins-nexus.retailinmotion.com-docker', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        script{
                            def shortLibraryName = libraryName.replace('libraryandroid','')
                            echo "Short library name: ${shortLibraryName}"
                            filePath = "./${shortLibraryName}/build/outputs/aar/${shortLibraryName}-release.aar"
                            def exists = fileExists filePath
                            if (exists){
                                echo "Build artifact: ${filePath}"
                            }
                            else{
                                error("Could not locate the build output at '${filePath}'")
                            }
                            nexusUploadUrl = "${env.RiMMavenRelease}com/retailinmotion/${shortLibraryName}/${versionInfo.SafeInformationalVersion}/${shortLibraryName}-${versionInfo.SafeInformationalVersion}.aar"
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