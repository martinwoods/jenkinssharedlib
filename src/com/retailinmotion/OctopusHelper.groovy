package com.retailinmotion;
/*
* 	Class Name: Octopus Helper
*	Purpose: 	Provides helper functions for communicating with Octopus deploy server
	Keith Douglas
	Dec 2017
	keith.douglas@retailinmotion.com
*/


/*
* Lookup the correct Octopus Deploy server to use for this Jenkins server
* Parameters:
			jenkinsURL - URL of the jenkins server, typically env.JENKINS_URL
  Returns: 
  Hashmap containing;
			url
			credentialsId
			toolName
			
*/ 
def getServer(jenkinsURL){
	def octopus=[:]
	
	if ( jenkinsURL.contains("rimdub-jen-01") ){	
		octopus['url']="http://octopus.rim.local"
		octopus['credentialsId']="OctopusRimLocalAPIKey"
		octopus['toolName']="Octo CLI"
	} else {
		octopus['url']="http://rim-build-05"
		octopus['credentialsId']="OctopusAPIKey"
		octopus['toolName']="Octo CLI"
	}
	println "Selected Octopus at ${octopus.url} for build server $jenkinsURL"
	return octopus
}

/*
*	Push the given package to the correct Octopus deploy server for this Jenkins build server
*/

def listDeployments (jenkinsURL, tenant, environment){
	
	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		powershell """
					&'${tool("${octopusServer.toolName}")}\\Octo.exe' list-deployments --tenant=${tenant} --environment=${environment} --server ${octopusServer.url} --apiKey ${APIKey}
				"""
	}
}



def pushPackage (jenkinsURL, packageFile){
	
	def octopusServer=getServer(jenkinsURL)
	println "Pushing package $packageFile to ${octopusServer.url}"
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		powershell """
					&'${tool("${octopusServer.toolName}")}\\Octo.exe' push --package $packageFile --server ${octopusServer.url} --apiKey ${APIKey}
				"""
	}
}

/*
* Create a release and deploy it 
*/
def deploy(jenkinsURL, project, packageString, deployTo, extraArgs){

	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		powershell """
				&'${tool("${octopusServer.toolName}")}\\Octo.exe' --create-release --waitfordeployment --progress --project "$project" --packageversion $packageString --version $packageString --deployTo "$deployTo" $extraArgs --server ${octopusServer.url} --apiKey ${APIKey}
				"""
	}
}

/*
* Create an octopus release based on package strings
*/
def createRelease(jenkinsURL, project, releaseVersion, packageArg = "", channel="", extraArgs = ""){

	def octopusServer=getServer(jenkinsURL)
	def optionString=""
	if ( packageArg != "" ){
		optionString = " --package \"$packageArg\""
	} else {
		optionString = " --packageversion \"$releaseVersion\""
	}
	
	if ( channel != ""){
		optionString += " --channel \"$channel\""
	}
	
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
	
		def commandOptions="--create-release --project \"$project\" $optionString --force --version $releaseVersion $extraArgs --server ${octopusServer.url} --apiKey ${APIKey}"
		
		if(isUnix()){
			sh "docker run --rm -v $(pwd):/src octopusdeploy/octo ${commandOptions}"
		} else {
			powershell """
				&'${tool("${octopusServer.toolName}")}\\Octo.exe' ${commandOptions}
				"""
		}
	}
}


/*
* Create an octopus release, reading the package versions from the packages found in the given folder
*/
def createReleaseFromFolder(jenkinsURL, project, releaseVersion, packagesFolder, extraArgs = ""){

	def octopusServer=getServer(jenkinsURL)
	
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		powershell """
				&'${tool("${octopusServer.toolName}")}\\Octo.exe' --create-release --project "$project" --packagesFolder "$packagesFolder" --version $releaseVersion $extraArgs --server ${octopusServer.url} --apiKey ${APIKey}
				"""
	}
}



