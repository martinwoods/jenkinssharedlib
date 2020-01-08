package com.retailinmotion;

/*
TO DO
- get webhook setup on one BluetoothLibraryAndroid repo - see example: http://bitbucket.rim.local:7990/plugins/servlet/webhooks/projects/DEVOPS/repos/terraform-eks/details/66
- confirm library repository with Dev and get Maven setup on Nexus
- confirm build steps with Dev
- properly set http://rim-build-05:8080/view/work-in-progress/job/bluetoothlibraryandroid/ to test with
- https://jenkins.io/doc/book/pipeline/shared-libraries
- create andriodLibBuildHelper shared lib with function androidLibBuild(params) to have the pipeline steps
- Jenkinsfile in repo should just import the lib "andriodLibBuildHelper@feature/DEVOPS-316" and call androidLibBuild(params) function defined in the lib
- investigate solutions for adding tenant customisations - Dev & DevOps
*/

def androidLibBuild(test){
    println "androidLibBuild Test value: --${test}--"
    def jkurl = getServer(env.JENKINS_URL)
    println "Jenkins: --${jkurl}--"
}