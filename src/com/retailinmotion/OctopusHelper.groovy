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
	
	if ( jenkinsURL.contains("10.25.1.200") ){	
		octopus['url']="http://10.25.1.200"
		octopus['credentialsId']="OctopusAPIKey"
		octopus['toolName']="Octo CLI"
	} else if ( jenkinsURL.contains("rim-build-05") ){	
		octopus['url']="http://rim-build-05"
		octopus['credentialsId']="OctopusAPIKey"
		octopus['toolName']="Octo CLI"
	} else if ( jenkinsURL.contains("rimdub-esb-01") ){	
		octopus['url']="http://rim-build-05"
		octopus['credentialsId']="OctopusAPIKey"
		octopus['toolName']="Octo CLI"
	} else if ( jenkinsURL.contains("rimdev-build-06") ){	
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
def pushPackage (jenkinsURL, packageFile){
	
	def octopusServer=getServer(jenkinsURL)
	println "Pushing package $packageFile to ${octopusServer.url}"
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		powershell """
					&'${tool("${octopusServer.toolName}")}\\Octo.exe' push --package $packageFile  --server ${octopusServer.url} --apiKey ${APIKey}
				"""
	}
}

def deploy(jenkinsURL, project, packageString, deployTo, extraArgs){

	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		powershell """
				&'${tool("${octopusServer.toolName}")}\\Octo.exe' --create-release --waitfordeployment --progress --project "$project" --packageversion $packageString --version $packageString --deployTo "$deployTo" $extraArgs --server ${octopusServer.url} --apiKey ${APIKey}
				"""
	}
}