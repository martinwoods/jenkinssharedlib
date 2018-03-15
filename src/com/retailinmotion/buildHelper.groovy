package com.retailinmotion;
/*
* 	Class Name: Builder Helper
*	Purpose: 	Provides helper functions for Jenkins pipeline scripts
	Keith Douglas
	Dec 2017
	keith.douglas@retailinmotion.com
*/

/*
* 	Name: 		getGitHash
*	Purpose: 	Gets current git hash, both full version and short
*	Parameters:	
				workingDir - Path of git repo we are working in
*	Returns:	Hashmap containing;
					full
					short
*/
def getGitHashes (){
	def hashes=[:]
	// Get the short and long git hashes for this build
	
	hashes['short'] = powershell(returnStdout: true, script: '''
	git log -n 1 --pretty=format:'%h'
	''').trim()
	
	hashes['full'] = powershell(returnStdout: true, script: '''
	git log -n 1 --pretty=format:'%H'
	''').trim()

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
		if ( featureInfo ) {
			buildInfo['featureName'] = featureInfo[0][1]
		} else {
			buildInfo['featureName'] = longFeatureName
		}
		buildInfo['longFeatureName']= longFeatureName
	} else { // otherwise, it's a branch build
		buildInfo['buildType'] = "branch"
		buildInfo['featureName'] = branchName
	}

	return buildInfo
}

// Assemble the package string and strip out any characters not allowed in a SemVer 2.0.0 compatible version, as required by Octopus (https://semver.org/spec/v2.0.0.html)
def getPackageName (assemblyInfo, buildInfo, gitHashes, buildNumber ){
	def packageString="${assemblyInfo.Major}.${assemblyInfo.Minor}.${assemblyInfo.Build}.$buildNumber-${buildInfo.buildType}+Branch.${buildInfo.featureName}.Sha.${gitHashes.short}".replaceAll(/[^0-9A-Za-z-\.\+]/, "");
	
	return packageString
}

/*
* getXMLNodeValue - Utility function to get the value of an xml node from a given xml file
*
*/
def getXMLNodeValue(filePath, nodeName){
    def xml=new XmlSlurper().parse(filePath)
	println "Looking for $nodeName in $filePath"
	xml.PropertyGroup.children().each { node -> println node.name()}
    def data=xml.PropertyGroup.children().find{ node ->
      node.name() == nodeName
    }

	return data.text()
}

/*
* getCSProjVersion
* Read the version string from a csproj file and return a map containing the Major/Minor/Build version
*/
def getCSProjVersion(filePath){
  
  def nodeName='Version'
  echo "Reading version from $filePath"
  def version=getXMLNodeValue(filePath, nodeName)
  
  def vers = version.tokenize('.')
  
  def versionInfo = [:]
  versionInfo.Major = vers[0].toString();
  versionInfo.Minor = vers[1].toString();
  versionInfo.Build = vers[2].toString();
  
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
        changeString = " - No new changes"
    }
    return changeString
}