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
	//println "Octo command is; ${commandOptions}"
	if(os == "linux"){
		output=sh returnStdout: true, script: "docker run --rm -v \"\$(pwd)\":/src ${DockerPullRIM}/octopusdeploy/octo ${commandOptions}"
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

// Get Commit Ids and Comments from Current Build Change Log
@NonCPS
def getCommitData() {
	def changeCommitId= []
	def changeMsg= []
	def changeLogSets = currentBuild.changeSets
	for (int i = 0; i < changeLogSets.size(); i++) {
		def entries = changeLogSets[i].items
		for (int j = 0; j < entries.length; j++) {
			def entry = entries[j]
			changeCommitId.add(entry.commitId)
			changeMsg.add(entry.msg)
		}
	}

	return [changeCommitId, changeMsg]
}

// Create Map for commit data
def getCommitDataMap() {

	def (commitId, msg)  = getCommitData()

	def commit = [
		Id: "",
		Comment: ""
	]

	def commitList = []
	
	for (int i = 0; i < commitId.size(); i++) {

		commit = [
			Id: "",
			Comment: ""
		]

		commit.Id = "${commitId[i]}"
		commit.Comment = "${msg[i]}"
		commitList.add(commit)
	}

	return commitList
}

// Regex to filter packageId & packageString from packageFile name
/*
Example package paths that will be parsed as expected; 

terraform_eks.261.1.1-DEVOPS-73.PR-86.97-Branch.PR-86.Sha.4a8b226.zip
artifacts/KubisiOS.0.13.0-KUBIS-538-AircraftInfoBookingGrouping.PR-273.10-Branch.PR-273.Sha.0d8a4bd.zip
artifacts\KubisiOS.0.13.0-KUBIS-538-AircraftInfoBookingGrouping.PR-273.10-Branch.PR-273.Sha.0d8a4bd.zip
artifacts\VectorReports.2.1911.0-develop.174-Branch.develop.Sha.b150104015.zip
some\long\path\artifacts\VectorReports.2.1911.0-develop.174-Branch.develop.Sha.b150104015.zip
artifacts\vRec.Jobs.2.1910.1-develop.0-Branch.develop.Sha.45f4ee4.zip
vRec.Jobs.2.1910.1-develop.0-Branch.develop.Sha.45f4ee4.zip
vRec.Jobs.2.1910.0-CATHAY-Stage.9-Branch.feature-2.1910-CATHAY-Stage.Sha.cc2dd4e.zip
vRec.Jobs.2.1907.1-VECTWO-33804.27-Branch.feature.Sha.355ad14.zip
vRec.Jobs.2.1907.0-vesb-release-version-fix.0-Branch.feature-2.1907-vesb-release-version-fix.Sha.a1c4b5a.zip
a/long/path/vRec.Jobs.2.1910.1-develop.0-Branch.develop.Sha.45f4ee4.zip
a-package.2.1910.1-develop.0-Branch.develop.Sha.45f4ee4.zip
some.other.package-version_something.2.1910.1-develop.0-Branch.develop.Sha.45f4ee4.zip
vPack2Codebase.2.1910.1.387-DEVOPS-141.17-Branch.feature-DEVOPS-141.Sha.6627710ba.zip
vPack2Codebase.2.1909.1.309-14.Branch.master.Sha.2e37ef5ef.zip
artifacts\vPack2Codebase.2.1910.1.411-VECTWO-31420-vpack-weak-pwd-management.2-Branch.feature-VECTWO-31420-vpack-weak-pwd-management.Sha.0a8476cb8.zip
artifacts\vPack2Codebase.2.1911.0.410-beta.0-Branch.release-2.1911.Sha.80525e546.zip
SQLSVN.1.0.0.19-branch+Branch.develop.Sha.6ae7de4.zip
v3WM.1.0.0.404-feature+Branch.VECTWO-18544.Sha.830267e.zip
RiM.Vector.Bezier.Services.Storage.1.0.2.6-branch+Branch.master.Sha.1df53b1.zip
EposWebService.2.1909.1-VECTWO-33227.2-Branch.VECTWO-33227.Sha.4a54a04.zip
EposWebService.2.1909.1-PullRequest0032.2-Branch.PR-32.Sha.4a54a04.zip
A.Package.With.Dots.1.2.3-abc123.zip
package_with_underscores.3.1.2.zip
artifacts/vPosAndroid.3.0.5077-ICE-release+Branch.-2.Sha.b0085cded.zip

*/
@NonCPS
def getPackageId(packageFile) {
	println "PackageFile is; ${packageFile}"
    def match = (packageFile  =~ /^(?:.*[\\\/])*([a-zA-Z0-9_\-\.]+?)(?:(?=\.\d+\.\d+\.\d+(?:\.|-).*))\.(.*)\.zip/)
	def packageId=match[0][1]
	def packageString=match[0][2]

	return [packageId, packageString]
}

// Push job Build Information to Octopus Deploy for given package
def buildInformation (jenkinsURL, packageFile, space="Default") {
	
	def ownerName
	def os=checkOs()
	if(os == "linux" || os == "macos"){
		ownerName=sh returnStdout: true, script: 'gitRemoteGetUrlOrigin=$(git config --get remote.origin.url) ; gitRemoteDirName=$(dirname ${gitRemoteGetUrlOrigin}) ; basename ${gitRemoteDirName}'
		ownerName=ownerName.trim()
		projectName=sh returnStdout: true, script: 'gitRemoteGetUrlOrigin=$(git config --get remote.origin.url) ; basename ${gitRemoteGetUrlOrigin} |  sed \'s/.git//g\''
		projectName=projectName.trim()
	} else if (os == "windows") {
		ownerName=powershell returnStdout: true, script: """
				Split-Path (Split-Path (& git config --get remote.origin.url)) -Leaf
			"""
		ownerName=ownerName.trim()
		projectName=powershell returnStdout: true, script: """
				(Split-Path (& git config --get remote.origin.url) -Leaf).Replace('.git','')
		"""
		projectName=projectName.trim()

	} else {
		println "Unable to run push Build Information, unrecognised OS $os"
		exit 1
	}

	// Define Build Information groovy map
	def map = [
		BuildEnvironment: "Jenkins",
		CommentParser: "Jira",
		BuildNumber: "${env.BUILD_NUMBER}",
		BuildUrl: "${env.BUILD_URL}",
		VcsType: "Git",
		VcsRoot: "http://bitbucket.rim.local:7990/projects/${ownerName}/repos/${projectName}",
		Commits: getCommitDataMap()
	]

	// Convert groovy map to json
	def jsonStr = JsonOutput.toJson(map)

	// Make json pretty
	def jsonBeauty = JsonOutput.prettyPrint(jsonStr)
	
	println jsonBeauty // ADDING TO TROUBLESHOOT ISSUE

	// Create json file containing pretty json text
	writeFile(file:'buildinfo.json', text: jsonBeauty)
	
	// get packageId and packageString from getPackageId method
	def (packageId, packageString)  = getPackageId(packageFile)

	// Constuct build-information octo command 
	def octopusServer=getServer(jenkinsURL)
	println "Pushing package Build Information to ${octopusServer.url}"
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
     		def commandOptions="build-information --server=${octopusServer.url} --apiKey=${APIKey} --package-id=$packageId --version=$packageString --file=\"buildinfo.json\" --space \"$space\"  --logLevel=verbose --overwrite-mode=OverwriteExisting"
    
			println commandOptions // ADDING TO TROUBLESHOOT ISSUE

	return execOcto(octopusServer, commandOptions)
	}
}

def pushPackage (jenkinsURL, packageFile, space="Default"){
	
	def octopusServer=getServer(jenkinsURL)
	println "Pushing package $packageFile to ${octopusServer.url}"
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions="push --package $packageFile --overwrite-mode=OverwriteExisting --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		
		buildInformation(jenkinsURL, packageFile)

		return execOcto(octopusServer, commandOptions)
	}
}

/*
* Create a release and deploy it 
*/
def deploy(jenkinsURL, project, packageString, deployTo, extraArgs, space="Default", packageArg = ""){
	def octopusServer=getServer(jenkinsURL)
	
	def optionString=""
	if ( packageArg && packageArg != "" ){
		optionString = " --package=\"$packageArg\""
	} else {
		optionString = " --packageversion \"$packageString\""
	}

	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions=" --create-release --ignoreexisting --waitfordeployment --deploymenttimeout=\"00:20:00\" --progress --project \"$project\" $optionString --version $packageString --deployTo \"$deployTo\" $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		return execOcto(octopusServer, commandOptions)
	}
}

/*
* Deploy an existing release
*/
def deployExisting(jenkinsURL, project, releaseVersion, deployTo, extraArgs, space="Default"){

	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions=" --deploy-release --deploymenttimeout=\"00:20:00\" --progress --project \"$project\" --version $releaseVersion --deployTo \"$deployTo\" $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		
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
		optionString = " --package=\"$packageArg\""
	} else {
		optionString = " --packageversion \"$releaseVersion\""
	}
	
	if ( channel && channel != "" ){
		optionString += " --channel \"$channel\""
	}
	
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {			
		def commandOptions="--create-release --ignoreexisting --project \"$project\" $optionString --force --version $releaseVersion $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\""
		return execOcto(octopusServer, commandOptions)
	}
}


/*
* Create an octopus release, feeding required packages from the given folder
* The version is forced by default with the --packageversion switch, but can be overwritten
* To overwrite the default version, call individual packages in the $extraArgs in this format:
* --package=StepName:Version (more info on https://octopus.com/docs/octopus-rest-api/octopus-cli/create-release)
* Forcing the version is to prevent releases being created with other packages (see DEVOPS-738)
*/
def createReleaseFromFolder(jenkinsURL, project, releaseVersion, packagesFolder, extraArgs = "", space="Default"){

	def octopusServer=getServer(jenkinsURL)
	withCredentials([string(credentialsId: octopusServer.credentialsId, variable: 'APIKey')]) {		
		def commandOptions="--create-release --ignoreexisting --project \"$project\" --packagesFolder \"$packagesFolder\" --packageversion $releaseVersion --version $releaseVersion $extraArgs --server ${octopusServer.url} --apiKey ${APIKey} --space \"$space\" "
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
