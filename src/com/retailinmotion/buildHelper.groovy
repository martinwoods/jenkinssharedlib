package com.retailinmotion;

import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonSlurperClassic

/*
* 	Class Name: BuildHelper
*	Purpose: 	Provides helper functions for Jenkins pipeline scripts
	Keith Douglas
	Dec 2017
	keith.douglas@retailinmotion.com
*/

/*
* Name: getGitVersionInfo
* Purpose: Get output of GitVersion command 
* Parameters: 	dockerImageOrToolPath - can be either a path to gitversion.exe, or the name of a docker image to use
*					if the given path exists, it will be called directly, otherwise, try to run a docker image
				dockerContext - If the command is to be run using docker, pass in the docker global variable from Jenkins pipeline
* 				subPath - allows specifying a subfolder in the repo to run gitversion (e.g. when there are multiple projects in a repo)
* 			
* Returns:		returns a JSON object containing the full output from gitversion
*				
* Examples:
				To call this function and use a docker image (typically on linux build agents)
					def versionInfo=buildHelper.getGitVersionInfo(env.GitVersionImage, docker)
					Note: GitVersionImage should be a variable matching the name of a docker image which can exec GitVersion.exe using mono, e.g. nexus.retailinmotion.com:5100/rim-gitversion:4.0.0-beta.14
					
				To use a tool from configured Jenkins Custom Tools (typicall on windows build agents)
					def gitVersionTool=tool name: 'GitVersion', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
					def versionInfo=buildHelper.getGitVersionInfo(gitVersionTool)
*/
def getGitVersionInfo(dockerImageOrToolPath, dockerContext=null, subPath =null){
	def args
	def gitVersionExe
	def useTool=false
	def useDocker=false
	if(dockerContext != null){
		useTool=false
		useDocker=true
	} else {
		useTool=true
		useDocker=false
		// Check the path if we are using the tool
		if (!dockerImageOrToolPath.toString().endsWith(".exe")){
			gitVersionExe=new File(dockerImageOrToolPath, "GitVersion.exe") 
		} else {
			gitVersionExe= new File(dockerImageOrToolPath)
		}
		
		try {
			gitVersionExe.getCanonicalPath();
		}
		catch (IOException e) {
		   echo "Couldn't find direct path to exe, and no dockerContext was passed. Unable to call GitVersion"
		   exit 1
		}
	}
		
	// Parse the subPath argument
	if(subPath != null){
		subPath=subPath.toString().replaceAll("\\\\", "/")
		if(!subPath.startsWith("/")){
			subPath="/" + subPath
		}
	} else {
		subPath=""
	}
	
	if(useTool){
		// call the tool directly (intended for use on windows systems)
		// set flag to prevent git tools error
		env.IGNORE_NORMALISATION_GIT_HEAD_MOVE=1
		withEnv(["gitVersionExe=${gitVersionExe}", "subPath=${subPath}"]) {
			powershell '''
			&"$($env:gitVersionExe)" "$($env:WORKSPACE)$($env:subPath)" | Out-File gitversion.txt -Encoding ASCII -Force
			If($LASTEXITCODE -ne 0){ 
				Get-Content gitversion.txt
			}
			'''
		}
	} else if (useDocker){
		// Execute the command inside the given docker image (intended for use on linux systems)
		dockerContext.image(dockerImageOrToolPath).inside("-v \"$WORKSPACE:/src\" -e subPath=\"$subPath\" -e args=\"$args\" -e IGNORE_NORMALISATION_GIT_HEAD_MOVE=1"){
			sh '''
				mono /usr/lib/GitVersion/tools/GitVersion.exe /src${subPath} > gitversion.txt 
			'''
		}
	} else {
		echo "Both useTool and useDocker are false, this shouldn't be possible, something has gone wrong in getGitVersionInfo"
		exit 1
	}
	
	def output = readFile(file:'gitversion.txt')
	def json = new JsonSlurperClassic().parseText(output)
	// Add a helm-safe version for strings which can contain a + symbol
	// This is due to https://github.com/helm/helm/issues/1698
	// Not all charts (private and public) are calling replace when referencing .Chart.Version,
	// so make it available here for use to avoid deploy time issues
	json.SafeFullSemVer=json.FullSemVer.toString().replaceAll("\\+", "-").replaceAll("/", "-").replaceAll("\\\\", "-")
	// The default informational version can be very long for feature branches, 
	// so make a copy of the original as FullInformationalVersion and create a new version with a shorter format	
	json.FullInformationalVersion=json.InformationalVersion
	
	// Replace the long git hash with the short version, and if the build metadata portion repeats the prereleaselabel, remove it
	def gitHashes=getGitHashes()
	def preReleaseLabel=json.PreReleaseLabel
	json.InformationalVersion=json.InformationalVersion.replace(gitHashes.full, gitHashes.short).replaceAll("_", "-").replace("/${preReleaseLabel}", "")
	
	// If the full branch name is too long, it can cause issues when octopus unpacks the archive due to path length restrictions in windows
	// If this is a branch which references a JIRA VECTWO ticket, shorten the prereleaselabel to just the ticket number without any other decoration to keep the package name short
	if( preReleaseLabel.contains("VECTWO")) {
		def jiraRef=(preReleaseLabel =~ /(VECTWO\-{1}[0-9]*)(.*)/)		
		json.InformationalVersion=json.InformationalVersion.replace(preReleaseLabel, jiraRef[0][1])
	}
	
	// and if the branchname contains a VECTWO reference with a long name, shorten it to just the ticket number in the informational version
	if (json.BranchName.contains("VECTWO")){
		def match=(json.BranchName =~ /(VECTWO\-{1}[0-9]*)(.*)/)
		json.InformationalVersion=json.InformationalVersion.replaceAll(/(VECTWO\-{1}[0-9]*)([A-Za-z0-9\-\_]*)/, match[0][1])
	}
	
	json.SafeInformationalVersion=json.InformationalVersion.toString().replaceAll("\\+", "-").replaceAll("/", "-").replaceAll("\\\\", "-").replaceAll("_", "-")
	
	// Since we are changing the tag in gitversion.yml for some repos, parse the prerelease label from the branchname 
	if(json.BranchName.contains("/")){
		json.PackagePreRelease=json.BranchName.substring(0, env.BRANCH_NAME.indexOf("/"))
	} else {
		json.PackagePreRelease=json.BranchName
	}
	
	
	
	return json
	
}

/*
*	Name: 		packageHelmChart
*	Purpose: 	Uses helm builder docker image to package a given helm chart
*	
*	Parameters:
				chartName - Name of chart to package, directory containing chart.yml must match this
				srcDir 	-	Source directory for charts
				targetDir -	Where to output packaged chart .tgz file
				version -	Version number to pass to chart, 
							Note: This will be used for appVersion, Chart version and image tag inside values.yaml
						
				dockerContext - 	docker variable from jenkins pipeline
				helmImage - Name of docker image to use containing helm and kubectl executables
*/
def packageHelmChart(chartName, srcDir, targetDir, version, dockerContext, helmImage){
	echo "Packaging $chartName"

	dockerContext.image(helmImage).inside("-e targetDir=\"$targetDir\" -e srcDir=\"$srcDir\" -e chartName=\"$chartName\" -e version=\"$version\"" ) { 
		sh '''
			mkdir -p $targetDir/$chartName
			sed -i s/"  tag: latest"/"  tag: $version"/ig "$srcDir/$chartName/values.yaml"
			helm package --version $version --app-version $version -d $targetDir/$chartName $srcDir/$chartName
		'''
		
	}
}

/*
*	Name: 		pushHelmCharts
*	Purpose:	Update helm repository with new charts in the specified directory
*	
*	Parameters:
*				chartDir	-	Source directory containing packaged helm charts
				helmRepo	-	URL for target helm repository
				userColonPassword - username and password for helm repo in username:password format
				dockerContext -	docker variable from Jenkins pipeline
				helmImage - Name of docker image to use containing helm and kubectl executables
*/
def pushHelmCharts(chartDir, helmRepo, userColonPassword, dockerContext, helmImage){

// use the helm client image to update the index file and push new charts to the repo
	dockerContext.image(helmImage).inside("-e HELMREPO=$helmRepo -e repoAuth=$userColonPassword -e chartDir=\"$chartDir\"" ) { 
		sh '''
			wget -q $HELMREPO/index.yaml
			helm repo index --url $HELMREPO --merge index.yaml $chartDir
			curl -s -u $repoAuth --upload-file $chartDir/index.yaml $HELMREPO/index.yaml
  
			for file in $(find $chartDir -name *.tgz)
			do
				chartFile=$(basename $file)
				chartName=$(basename $(dirname $file))
				curl -s -u $repoAuth --upload-file $file $HELMREPO/${chartName}/$chartFile
			done
		'''
		
	}
}
/*
* 	Name: 		getGitHash
*	Purpose: 	Gets current git hash, both full version and short
*	 :	
				workingDir - Path of git repo we are working in
*	Returns:	Hashmap containing;
					full
					short
*/
def getGitHashes (){
	def hashes=[:]
	// Get the short and long git hashes for this build
	if(isUnix()){
		hashes['short'] = sh(returnStdout: true, script: '''
	git log -n 1 --pretty=format:'%h'
	''').trim()
	
		hashes['full'] = sh(returnStdout: true, script: '''
	git log -n 1 --pretty=format:'%H'
	''').trim()
	} else {
		hashes['short'] = powershell(returnStdout: true, script: '''
	git log -n 1 --pretty=format:'%h'
	''').trim()
	
		hashes['full'] = powershell(returnStdout: true, script: '''
	git log -n 1 --pretty=format:'%H'
	''').trim()
	}
	return hashes
}

/*
* 	Name: 		getBranchInfo
*	Purpose: 	Reads branch path from GIT and parses build type and branch name
*	Parameters:	
				branchName - The branch path from GIT to parse
*	Returns:	Hashmap containing;
					buildType
					featureName
					longFeatureName
			Examples which will match;
			 feature/VECTWO-14795-vpos-dashboard-1
			 bugfix/VECTWO-15215-tcx-mastercontrol-containers
			 release/RC_20171106
			 VECTWO-19157-new-adjustment-reason-as-supplier (Branch containing VECTWO will be set to buildType of 'feature')
			 AirAsia_Branch (Default buildType of 'branch' will be set in this instance)
*/
def getBranchInfo(branchName){
	def buildInfo=[:]

	// Feature/release etc. branches will have a slash seperator
	if ( branchName.contains("/")) {

		def branchInfo =  (branchName =~ /(.*)(\\/)(.*)/ )

		buildInfo['buildType']=branchInfo[0][1]
		def longFeatureName=branchInfo[0][3]

		def featureInfo = longFeatureName =~ /([A-Z]*\-{1}[0-9]*)(.*)/
		// During transition to gitversion, branches created in gitversion style, 
  		// but still using the old incrementing number can end up just having a dash 
  		// In this case, use the longFeatureName instead
		if ( featureInfo && featureInfo[0][1] != "-") {
			buildInfo['featureName'] = featureInfo[0][1]
		} else {
			buildInfo['featureName'] = longFeatureName
		}
		buildInfo['longFeatureName']= longFeatureName
	} else if(branchName.contains("VECTWO")){ // sometimes, feature branches are created without feature/ prefix by mistake
		buildInfo['buildType']="feature"
		def longFeatureName=branchName

		def featureInfo = longFeatureName =~ /([A-Z]*\-{1}[0-9]*)(.*)/
		if ( featureInfo ) {
			buildInfo['featureName'] = featureInfo[0][1]
		} else {
			buildInfo['featureName'] = longFeatureName
		}
		buildInfo['longFeatureName']= longFeatureName
	} else { // otherwise, it's a mainline branch build
		buildInfo['buildType'] = "branch"
		buildInfo['featureName'] = branchName
		buildInfo['longFeatureName'] = branchName
	}

	return buildInfo
}

// Assemble the package string and strip out any characters not allowed in a SemVer 2.0.0 compatible version, as required by Octopus (https://semver.org/spec/v2.0.0.html)
def getPackageName (assemblyInfo, buildInfo, gitHashes, buildNumber ){
	def packageSuffix=getPackageSuffix(buildInfo, gitHashes);
	def packageString="${assemblyInfo.Major}.${assemblyInfo.Minor}.${assemblyInfo.Build}.$buildNumber${packageSuffix}".replaceAll(/[^0-9A-Za-z-\.\+]/, "");
	
	return packageString
}

def getPackageSuffix (buildInfo, gitHashes){
	def packageSuffix="-${buildInfo.buildType}+Branch.${buildInfo.featureName}.Sha.${gitHashes.short}".replaceAll(/[^0-9A-Za-z-\.\+]/, "");
	
	return packageSuffix
}


/*
* getXMLNodeValue - Utility function to get the value of an xml node from a given xml file
*
*/
def getXMLNodeValue(filePath, nodeName){
	def xml=new XmlSlurper().parse(filePath)
	def data=false
	xml.PropertyGroup.children().each { node -> 
		if(node.name() == nodeName) 
		{ 
		  data=node.text();  
		} 
	}
	return data
}
/*
* Get the value at the given xPath
*/
def getXMLPathValue(filePath, xpathQuery){	
	def xpath = XPathFactory.newInstance().newXPath()
	def builder     = DocumentBuilderFactory.newInstance().newDocumentBuilder()
	def inputStream = new FileInputStream(filePath)
	def records     = builder.parse(inputStream).documentElement
	return xpath.evaluate( xpathQuery, records )
}

/* 
* Get the version property in build.xml
*/
def getAntBuildXMLVersion(filePath) {
	def xPath='//project/property[@name="version"]/@value';
	def version=getXMLPathValue(filePath, xPath);
	echo "Found version $version in $filePath"
	def versionInfo = [:]
	if(version != false) {
		def vers = version.tokenize('.')
		
		versionInfo.Major = vers[0].toString();
		versionInfo.Minor = vers[1].toString();
		versionInfo.Build = vers[2].toString();
	  } else {
		println "Warning: No version found in $filePath, defaulting to 1.0.0"
		versionInfo.Major = "1"
		versionInfo.Minor = "0"
		versionInfo.Build = "0"
	  }
	return versionInfo
}

/*
* getCSProjVersion
* Read the version string from a csproj file and return a map containing the Major/Minor/Build version
*/
def getCSProjVersion(filePath){
  
  def nodeName='Version'
  def versionInfo = [:]
  echo "Reading version from $filePath"
  def version=getXMLNodeValue(filePath, nodeName)
  if(version != false && version != "") {
	def vers = version.tokenize('.')
	
	versionInfo.Major = vers[0].toString();
	versionInfo.Minor = vers[1].toString();
	versionInfo.Build = vers[2].toString();
  } else {
	println "Warning: No version found in $filePath, defaulting to 1.0.0"
	versionInfo.Major = "1"
	versionInfo.Minor = "0"
	versionInfo.Build = "0"
  }
  return versionInfo
}


/*
* 	Name: 		getAsssemblyInfo
*	Purpose: 	Reads version information from given assemblyinfo.cs file
* 	Parameters:	
				filePath - Path to the assemblyinfo.cs file to read version info from
*	Returns:	Hashmap containing;
					Major
					Minor
					Build
					Revision
					
	Keith Douglas
	Dec 2017
	keith.douglas@retailinmotion.com
*/
def getAssemblyInfo(filePath){

	echo "Reading assembly version info from ${filePath}"
	
	// Store the info in a timestamped file so that we don't clash with other build files
	Date d=new Date();
	def propsFile="versioninfo." + d.getTime() + ".properties"
	
	withEnv(["filePath=${filePath}", "propsFile=$propsFile"]) {
	// Get current version info from assemblyinfo.cs
	powershell '''# Read the current values set in the assemblyinfo
	# This can be then be modified/reused in Jenkins as required
	# Major Ver and Minor Ver will likely always come from AssemblyInfo, with Build and revision being overwritten at build time

	$filePath="$env:filePath"
	# Look for the assembly file version 
	$pattern = \'\\[assembly: AssemblyVersion\\("(.*)"\\)\\]\'
	# ignore lines that are commented out
	$commentpattern= \'\\/\\/.*\' 
	
	Get-Content $filePath | ForEach-Object{
		if($_ -match $pattern -and $_ -notmatch $commentpattern ){
			# We have found the matching line
			# Parse the version number
			$versionString=$matches[1]
			
			$fileVersion = [version]($versionString -replace "\\.\\*", "")
			\'AssemblyVersion("{0}")\' -f $fileVersion
			# output as a service message for team city to parse
			"Major:$($fileVersion.Major)" | Set-Content $env:propsFile
			"Minor:$($fileVersion.Minor)" | Add-Content $env:propsFile
			"Build:$($fileVersion.Build)" | Add-Content $env:propsFile
			"Revision:$($fileVersion.Revision)" | Add-Content $env:propsFile
		} 
	}
	EXIT 0'''
	}
	
	// read the contents of the properties file into a map
	textProps=readFile "$propsFile"
	props=readProperties text: "$textProps"				
	// clean up the temp file
	fileOperations([fileDeleteOperation(excludes: '', includes: "$propsFile")])

	
	return props
	
}


/*
* 	Name: 		updateAssemblyInfo
*	Purpose: 	Update all assemblyinfo.cs files under the given path with the specified version strings
*	Parameters:	
				versionPath - Path under which to recursively update assemblyinfo.cs files
				newVersion - String to use for the AssemblyVersion and AssemblyFileVersion parameters
				newInfoVersion - String to use for the AssemblyInformationalVersion parameter
				
	Keith Douglas
	Dec 2017
	keith.douglas@retailinmotion.com
*/
def updateAssemblyInfo (versionPath, newVersion, newInfoVersion) {
	
	echo "Updating assembly info at ${versionPath} to ${newVersion} / ${newInfoVersion}"
	
	withEnv(["assemblyVersion=${newVersion}",
			"assemblyInfoVersion=${newInfoVersion}", 
			"versionPath=${versionPath}"]) {
			
		powershell '''
			function Update-SourceVersion
			{
				Param ([string]$Version, 
					[string]$InfoVersion
				)
			   

				foreach ($o in $input) 
				{
					Write-Host "Updating  \'$($o.FullName)\' -> $Version"
				
					$assemblyVersionPattern = \'AssemblyVersion\\("[0-9]+(\\.([0-9]+|\\*)){1,3}"\\)\'
					$fileVersionPattern = \'AssemblyFileVersion\\("[0-9]+(\\.([0-9]+|\\*)){1,3}"\\)\'
					$infoVersionPattern = \'AssemblyInformationalVersion\\(".*"\\)\'
					$assemblyVersion = \'AssemblyVersion("\' + $version + \'")\';
					$fileVersion = \'AssemblyFileVersion("\' + $version + \'")\';
					$newInfoVersion = \'AssemblyInformationalVersion("\' + $InfoVersion + \'")\';

					# Update the file, check whether or not we need to update the info version too
					if( [string]::IsNullOrEmpty($InfoVersion)){
						(Get-Content $o.FullName) | ForEach-Object  { 
						   % {$_ -replace $assemblyVersionPattern, $assemblyVersion } |
						   % {$_ -replace $fileVersionPattern, $fileVersion }
						} | Out-File $o.FullName -encoding UTF8 -force
					} else {
						  (Get-Content $o.FullName) | ForEach-Object  { 
						   % {$_ -replace $assemblyVersionPattern, $assemblyVersion } |
						   % {$_ -replace $fileVersionPattern, $fileVersion } |
						   % {$_ -replace $infoVersionPattern, $newInfoVersion }
						} | Out-File $o.FullName -encoding UTF8 -force
					}
				}
			}
			function Update-AllAssemblyInfoFiles ( $version, $infoVersion, $path )
			{
				Write-Host "Searching \'$path\'"
			   foreach ($file in "AssemblyInfo.cs", "AssemblyInfo.vb" ) 
			   {
					get-childitem $path -recurse |? {$_.Name -eq $file} | Update-SourceVersion $version $infoVersion;
			   }
			}

			
			
			Update-AllAssemblyInfoFiles $env:assemblyVersion $env:assemblyInfoVersion $env:versionPath
			
		'''	
	}
}

/*
* Get the version number from the given file
* Increments the version number in the file using a file lock
* and returns the incremented version number
*/
def bumpVersion(filePath){
	echo "Bumping version at $filePath"
	lock("$filePath-lock"){
		buildString=readFile "$filePath"
		oldBuildNumber=buildString.toInteger()
		newBuildNumber=oldBuildNumber+1
		writeFile file: "$filePath" , text: "$newBuildNumber"
	}
	echo "Old build number was: $oldBuildNumber, bumped to: $newBuildNumber"
	return newBuildNumber
}

/*
*
* Get the commit hash of the most recent succesful build
* Borrowed from https://gist.github.com/ftclausen/8c46195ee56e48e4d01cbfab19c41fc0
*/
def getLastSuccessfulCommit() {
  def lastSuccessfulHash = null
  def lastSuccessfulBuild = currentBuild.rawBuild.getPreviousSuccessfulBuild()
  if ( lastSuccessfulBuild ) {
    lastSuccessfulHash = commitHashForBuild( lastSuccessfulBuild )
  }
  return lastSuccessfulHash
}

/**
 * Gets the commit hash from a Jenkins build object, if any
 */
@NonCPS
def commitHashForBuild( build ) {
  def scmAction = build?.actions.find { action -> action instanceof jenkins.scm.api.SCMRevisionAction }
  
  def hash=null
  
  try {
	hash=scmAction?.revision?.hash
	}
  catch (MissingPropertyException e){}
  
  return hash
}

/*
* Get the list of changes for the build
*/
def getChangeString() {
	def changeString=""
	def changeLogSets = currentBuild.changeSets
	for (int i = 0; i < changeLogSets.size(); i++) {
		def entries = changeLogSets[i].items
		for (int j = 0; j < entries.length; j++) {
			def entry = entries[j]
			echo "${entry.commitId} by ${entry.author} on ${new Date(entry.timestamp)}: ${entry.msg}"
			changeString+=entry.msg + "\n"
			
		}
	}
	
	if (!changeString) {
		changeString = " - Jenkins was unable to read changes"
	}
	return changeString
}

/*
*Get Git commit info that is not yet available in jenkins pipeline
 */
def getLastCommitAuthor( ){
	if(isUnix()){
		gitCommitter = sh(returnStdout: true, script: '''
	git show -s --pretty=%an
	''').trim()
	} else {
		gitCommitter = powershell(returnStdout: true, script: '''
	git show -s --pretty=%an
	''').trim()
	}
	return gitCommitter
}
/*
* Send Notifications - with slack initially - only notify if job is 'FAILED'
* Purpose: 	Send slack notifications from jenkins pipelines
*	Parameters:
*				buildStatus - String representing the status of the build
*				tokenCredentialId - String which identifies token to use - must match one avaiable token in jenkins
*				commitAuthor - String to use for the author of the last commit
*/
def sendNotifications( buildStatus, tokenCredentialId, commitAuthor, customMessage = null ){
	def subject = "Job '${env.JOB_NAME}: ${buildStatus}: '"
	def message = customMessage ?: "${subject} (${env.BUILD_URL}) The last commit was: ${env.GIT_COMMIT} by : ${commitAuthor}"

	buildStatus=buildStatus.toString().toUpperCase()

	if (buildStatus == 'FAILED' || buildStatus == 'FAILURE') {
		color = 'RED'
		colorCode = '#FF0000'
	}
	else if ( buildStatus == 'SUCCESS' ) {
		color = 'GREEN'
		colorCode = '#00FF00'
	}
	else if ( buildStatus == 'UNSTABLE' ) {
		color = 'YELLOW'
		colorCode  = '#FFF00'
	}
	else {
		color = 'BLUE'
		colorCode = '#008fff'
	}

	slackSend (color: colorCode, message: message, tokenCredentialId: tokenCredentialId )

}
