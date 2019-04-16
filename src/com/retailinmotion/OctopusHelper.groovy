package com.retailinmotion;


import java.util.regex.Matcher
import java.util.regex.Pattern
import groovy.json.JsonSlurperClassic

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
	
	if ( jenkinsURL.contains("rimdub-jen-01") &&  jenkinsURL.contains("sandbox") ){	 // allow manual override to the 'sandbox' octopus server
		octopus['url']="http://rim-build-05.rim.local"
		octopus['credentialsId']="OctopusSandboxAPIKey"
		octopus['toolName']="Octo CLI"
	} else if ( jenkinsURL.contains("rimdub-jen-01") ){	
		octopus['url']="http://octopus.rim.local"
		octopus['credentialsId']="OctopusRimLocalAPIKey"
		octopus['toolName']="Octo CLI"
	} else if ( jenkinsURL.contains("rimdev-build-06") &&  jenkinsURL.contains("sandbox") ){	 // allow manual override to the 'sandbox' octopus server
		octopus['url']="http://rim-build-05.rim.local"
		octopus['credentialsId']="OctopusAPIKey"
		octopus['toolName']="Octo CLI"
	} else if ( jenkinsURL.contains("rimdev-build-06") ){	
		octopus['url']="http://octopus.rim.local"
		octopus['credentialsId']="OctopusRimLocalAPIKey"
		octopus['toolName']="Octo CLI"
	}  else {
		octopus['url']="http://rim-build-05"
		octopus['credentialsId']="OctopusAPIKey"
		octopus['toolName']="Octo CLI"
	}
	println "Selected Octopus at ${octopus.url} for build server $jenkinsURL"
	return octopus
}

def execOcto(octopusServer, commandOptions){
	def output
	if(isUnix()){
		output=sh returnStdout: true, script: "docker run --rm -v \$(pwd):/src octopusdeploy/octo ${commandOptions}"
	} else {
		output=powershell returnStdout: true, script: """
			&'${tool("${octopusServer.toolName}")}\\Octo.exe' ${commandOptions}
			"""
	}
	println output
	return output.trim()
}
/*
*	Push the given package to the correct Octopus deploy server for this Jenkins build server
*/

def listDeployments (jenkinsURL, tenant, environment, space="Default"){
	
	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions="list-deployments --tenant=${tenant} --environment=${environment} --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		return execOcto(octopusServer, commandOptions)
	}
}



def pushPackage (jenkinsURL, packageFile, space="Default"){
	
	def octopusServer=getServer(jenkinsURL)
	println "Pushing package $packageFile to ${octopusServer.url}"
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions="push --package $packageFile --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		return execOcto(octopusServer, commandOptions)
	}
}

/*
* Create a release and deploy it 
*/
def deploy(jenkinsURL, project, packageString, deployTo, extraArgs, space="Default"){

	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions=" --create-release --waitfordeployment --progress --project \"$project\" --packageversion $packageString --version $packageString --deployTo \"$deployTo\" $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		
		return execOcto(octopusServer, commandOptions)
	}
}

/*
* Create an octopus release based on package strings
*/
def createRelease(jenkinsURL, project, releaseVersion, packageArg = "", channel="", extraArgs = "", space="Default"){

	def octopusServer=getServer(jenkinsURL)
	def optionString=""
	if ( packageArg && packageArg != "" ){
		optionString = " --package \"$packageArg\""
	} else {
		optionString = " --packageversion \"$releaseVersion\""
	}
	
	if ( channel && channel != "" ){
		optionString += " --channel \"$channel\""
	}
	
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions="--create-release --project \"$project\" $optionString --force --version $releaseVersion $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		return execOcto(octopusServer, commandOptions)
	}
}


/*
* Create an octopus release, reading the package versions from the packages found in the given folder
*/
def createReleaseFromFolder(jenkinsURL, project, releaseVersion, packagesFolder, extraArgs = "", space="Default"){

	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {		
		def commandOptions="--create-release --project \"$project\" --packagesFolder \"$packagesFolder\" --version $releaseVersion $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\" "
		return execOcto(octopusServer, commandOptions)
	}
}

/*
* Parse the output from a deployment to extract only relevant info 
* by removing the line wrapping adding by octo.exe 
* and extracting the JSON which follows the string '### Deployment Status JSON:'
*/
def parseDeployInfo(deployOutput){

	output=deployOutput.replaceAll(/\n             /, "").replaceAll(/\n/, "\\\\n")
	Pattern urlPattern = Pattern.compile("(### Deployment Status JSON: )(\\{(.*)\\})",Pattern.CASE_INSENSITIVE);
	Matcher matcher = urlPattern.matcher(output);
	
  def data
  if(matcher.getCount() > 0){
    // Return the helm notes json object if we matched the pattern
	data = new JsonSlurperClassic().parseText(matcher[0][2])
  } else {
    // otherwise, return a JSON object of the same format with a message
    data = new JsonSlurperClassic().parseText('{"name": "","info": {"status": {"resources": "","notes": "Unable to parse deployment status."},"first_deployed": {"seconds": 0,"nanos": 0},"last_deployed": {"seconds": 0,"nanos": 0},"Description": ""},"namespace": ""}')
  }
  return data
}



