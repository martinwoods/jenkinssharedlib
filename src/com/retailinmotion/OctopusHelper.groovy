package com.retailinmotion;


import java.util.regex.Matcher
import java.util.regex.Pattern
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import java.io.File
import java.io.FileWriter

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

def checkOs(){
    if (isUnix()) {
        def uname = sh script: 'uname', returnStdout: true
        if (uname.startsWith("Darwin")) {
            return "macos"
        }
        // Optionally add 'else if' for other Unix OS  
        else {
            return "linux"
        }
    }
    else {
        return "windows"
    }
}

def execOcto(octopusServer, commandOptions){
	def output
	def os=checkOs()
	if(os == "linux"){
		output=sh returnStdout: true, script: "docker run --rm -v \"\$(pwd)\":/src octopusdeploy/octo ${commandOptions}"
	} else if (os == "macos"){
		output=sh returnStdout: true, script: """
			'${tool("${octopusServer.toolName}")}/Octo' ${commandOptions}
			"""
	} else if (os == "windows") {
		output=powershell returnStdout: true, script: """
			&'${tool("${octopusServer.toolName}")}\\Octo.exe' ${commandOptions}
			"""
	} else {
		println "Unable to run octo for unrecognised OS $os"
		exit 1
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

// Get Commit Ids from Current Build Change Log
@NonCPS
def getChangeString() {
	def changeString=""
	def changeLogSets = currentBuild.changeSets
	for (int i = 0; i < changeLogSets.size(); i++) {
		def entries = changeLogSets[i].items
		for (int j = 0; j < entries.length; j++) {
			def entry = entries[j]
			changeString+=entry.commitId + "\n"
		}
	}

	if (!changeString) {
		changeString = " - Jenkins was unable to read changes"
	}
	return changeString
}

@NonCPS
def getPackageId(packageFile) {
	// Regex to filter packageId from packageFile name
	def match = (packageFile  =~ /^(.*?)\..*/)
	println match[0]
	def matchGroup1 = match.group(1)
	println matchGroup1
	def nextMatch = (matchGroup1 =~ /([^\\]+$)/)
	println nextMatch[0]
	nextMatch.group()
	def packageId = nextMatch.group()

	return packageId
}


// Push job metadata to Octopus Deploy for the given package
def pushMetadata (jenkinsURL, packageFile, space="Default") {
	
	def commitIds = getChangeString()
	println "Change String is equal to: ${commitIds}"

	// Define metadata groovy map
	def map = [
		BuildEnvironment: "Jenkins",
		BuildNumber: "${env.BUILD_NUMBER}",
		BuildUrl: "${env.BUILD_URL}",
		VcsType: "Bitbucket",
		VcsRoot: "${env.GIT_URL}",
		VcsCommitNumber: "${env.GIT_COMMIT}",
		Commits: [
			Id: "${commitIds}",
			Comment: ""
		]
	]

	// Convert groovy map to json
	def jsonStr = JsonOutput.toJson(map)

	// Make json pretty
	def jsonBeauty = JsonOutput.prettyPrint(jsonStr)
	println(jsonBeauty)

	// Create json file containing pretty json text
	writeFile(file:'metadata.json', text: jsonBeauty)
	echo bat(returnStdout: true, script: 'dir')
    echo bat(returnStdout: true, script: "type metadata.json")
	
	println "${packageFile}"

	def packageId = getPackageId(packageFile)

	def octopusServer=getServer(jenkinsURL)
	println "Pushing package metadata to ${octopusServer.url}"
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
     		def commandOptions="push-metadata --server=${octopusServer.url} --apiKey=${APIKey} --package-id=$packageId --version=$packageString --metadata-file=\"${env.WORKSPACE}\\metadata.json\" --space \"$space\""
      
    return execOcto(octopusServer, commandOptions)
	}
}

def pushPackage (jenkinsURL, packageFile, space="Default"){
	
	def octopusServer=getServer(jenkinsURL)
	println "Pushing package $packageFile to ${octopusServer.url}"
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions="push --package $packageFile --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""

		pushMetadata(jenkinsURL, packageFile)

		return execOcto(octopusServer, commandOptions)
	}
}

/*
* Create a release and deploy it 
*/
def deploy(jenkinsURL, project, packageString, deployTo, extraArgs, space="Default"){

	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions=" --create-release --waitfordeployment --deploymenttimeout=\"00:20:00\" --progress --project \"$project\" --packageversion $packageString --version $packageString --deployTo \"$deployTo\" $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		
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