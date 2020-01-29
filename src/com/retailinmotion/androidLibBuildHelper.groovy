package com.retailinmotion;

/*
TO DO
- get webhook setup on BluetoothLibraryAndroid repo - see example: http://bitbucket.rim.local:7990/plugins/servlet/webhooks/projects/DEVOPS/repos/terraform-eks/details/66
- confirm library repository with Dev and get Maven setup on Nexus
- confirm build steps with Dev
- properly set http://rim-build-05:8080/view/work-in-progress/job/bluetoothlibraryandroid/ to test with
- https://jenkins.io/doc/book/pipeline/shared-libraries
- create andriodLibBuildHelper shared lib with function androidLibBuild(params) to have the pipeline steps
- Jenkinsfile in repo should just import the lib "andriodLibBuildHelper@feature/DEVOPS-316" and call androidLibBuild(params) function defined in the lib
*/

def androidLibBuildSrc(BUILD_NUMBER){
/*     println "androidLibBuild Test value: --${test}--"
    def OctopusHelper = new com.retailinmotion.OctopusHelper()
    def jkurl = OctopusHelper.getServer(env.JENKINS_URL)
    println "Jenkins: --${jkurl}--" */

    def buildHelper = new com.retailinmotion.buildHelper()
    def versionInfo
    println "Jenkins build: " + BUILD_NUMBER
    println "Jenkins job url: " + JOB_URL


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

        }
    }
}