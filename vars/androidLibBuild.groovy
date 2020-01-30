def call () {
/* 	def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body() */

    def buildHelper = new com.retailinmotion.buildHelper()
    def OctopusHelper = new com.retailinmotion.OctopusHelper()
    def os = OctopusHelper.checkOs()
    def versionInfo
    def libraryName
    def filePath
    def nexusUploadUrl

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
            stage('Build'){
                steps {
                    bat './gradlew.bat cleanBuildCache'
                    bat './gradlew.bat assembleRelease'
                }
            }

            stage('Prep Nexus upload') {
                steps {
                    script{
                        if (os == 'linux' || os == 'macos'){
                            libraryName = sh 'basename `git remote get-url origin` .git'
                        }
                        else if (os == 'windows'){
                            libraryName = powershell(returnStatus: true, script: '(Split-Path (& git remote get-url origin) -Leaf).replace(".git","")') 
                        }
                        echo "Library name: ${libraryName}"
                        filepath = "${libraryName}/build/outputs/aar/${libraryName}-release.aar"
                        def exists = fileExists filePath
                        if (exists){
                            echo "Build artifact: ${filePath}"
                        }
                        else{
                            error("Could not locate the build output at '${filePath}'")
                        }
                        nexusUploadUrl = "${env.RiMMavenRelease}com/retailinmotion/${libraryName}/${versionInfo.SafeInformationalVersion}/${libraryName}-${versionInfo.SafeInformationalVersion}.aar"
                        echo "Uploading library to Nexus at ${nexusUploadUrl}"
                    }
                }
            }
            stage('Upload to Nexus'){
                steps{
                    withCredentials([usernamePassword(credentialsId: 'jenkins-nexus.retailinmotion.com-docker', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        script{
                            def uploadStatus
                            if (os == 'linux' || os == 'macos'){
                                uploadStatus = sh "curl.exe -u $USERNAME:$PASSWORD --upload-file ${filePath} ${nexusUploadUrl}"
                            }
                            else if (os == 'windows'){
                                uploadStatus = powershell(returnStatus: true, script: "curl.exe -u $USERNAME:$PASSWORD --upload-file ${filePath} ${nexusUploadUrl}")
                            }
                            if (uploadStatus != 0) {
                                error("Could not upload library to Nexus: ${uploadStatus}")
                            }
                            else{
                                echo "Nexus upload successful"
                            }
                        }
                    }
                }
            }

/*             stage('Clean Workspace'){
                steps {
                    cleanWs()
                }
            } */
        }
    }

}