def call () {
/* 	def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body() */

    def buildHelper = new com.retailinmotion.buildHelper()
    def versionInfo

    pipeline {
        agent any
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

            stage('Clean Workspace'){
                steps {
                    cleanWs()
                }
            }
        }
    }

}