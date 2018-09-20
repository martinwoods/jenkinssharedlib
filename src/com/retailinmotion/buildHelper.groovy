package com.retailinmotion;

import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonSlurperClassic

/*
* 	Class Name: Builder Helper
*	Purpose: 	Provides helper functions for Jenkins pipeline scripts
	Keith Douglas
	Dec 2017
	keith.douglas@retailinmotion.com
*/

class buildHelper implements Serializable {

	def script
	
	buildHelper(){
		this.script=null 
	}
	buildHelper(script){
		this.script=script
	}

	
	
	/*
	* Name: getGitVersionInfo
	* Purpose: Get output of GitVersion command 
	* Parameters: 	dockerImageOrToolPath - can be either a path to gitversion.exe, or the name of a docker image to use
	*					if the given path exists, it will be called directly, otherwise, try to run a docker image
	* 				subPath - allows specifying a subfolder in the repo to run gitversion (e.g. when there are multiple projects in a repo)
	* 				variable - takes the name of a single variable to be returned from GitVersion, if omitted, a JSON object is returned
	*/
	def getGitVersionInfo(dockerImageOrToolPath, subPath =null, variable=null){
		def args
		def gitVersionExe
		def useTool=false
        def useDocker=false
		// Check if we were given a path to the gitversion.exe
        if (!dockerImageOrToolPath.toString().endsWith(".exe")){
			    gitVersionExe=new File(dockerImageOrToolPath, "GitVersion.exe") 
		} else {
			gitVersionExe= new File(dockerImageOrToolPath)
		}
		
		// If the exe exists and is a real path, use that, if not, assume the given string is a docker image to run
		try {
           gitVersionExe.getCanonicalPath();
           useTool=true
           useDocker=false
		   script.echo "Found $gitVersionExe, will call tool directly"
        }
        catch (IOException e) {
           useTool=false
           useDocker=true
		   script.echo "Couldn't find direct path to exe, will try to call docker image using $dockerImageOrToolPath"
        }
		
		// Parse the subPath argument
		if(subPath != null){
			subPath=subPath.toString().replaceAll('\\', '/')
			if(!subPath.startsWith("/")){
				subPath="/" + subPath
			}
		} else {
			subPath=""
		}
		// Check if we need to query a specific variable from gitversion
		if(variable != null){
			args="/showvariable $variable"
		} else {
			args=""
		}
		if(useTool){
			// call the tool directly (intended for use on windows systems)
			script.echo "Command is &\"$gitVersionExe\" \"${WORKSPACE}${subPath}\" $args "
			
			script.withEnv(["gitVersionExe=${gitVersionExe}", "subPath=$subPath", "args=$args"]) {
				script.powershell '''
					&"$env:gitVersionExe" "$($env:WORKSPACE)$($env:subPath)" $($args) > gitversion.txt
				'''
			}
		} else if (useDocker){
			// Execute the command inside the given docker image (intended for use on linux systems)
			script.docker.image(dockerImageOrToolPath).inside("-v \"$script.WORKSPACE:/src\" -e subPath=\"$subPath\" -e args=\"$args\""){
				script.sh '''
					mono /usr/lib/GitVersion/tools/GitVersion.exe /src${subPath} ${args} > gitversion.txt
				'''
			}
		} else {
			script.echo "Both useTool and useDocker are false, this shouldn't be possible, something has gone wrong in getGitVersionInfo"
			exit 1
		}
		
		def output = script.readFile(file:'gitversion.txt')
		
		// If a single variable was specified, return the output directly
		if(variable != null){
			return output
		} else { // otherwise, return a json object
			def json = new JsonSlurperClassic().parseText(output)
			return json
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
		
		hashes['short'] = script.powershell(returnStdout: true, script: '''
		git log -n 1 --pretty=format:'%h'
		''').trim()
		
		hashes['full'] = script.powershell(returnStdout: true, script: '''
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
			if ( featureInfo ) {
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
		
		script.withEnv(["filePath=${filePath}", "propsFile=$propsFile"]) {
		// Get current version info from assemblyinfo.cs
		script.powershell '''# Read the current values set in the assemblyinfo
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
		textProps=script.readFile "$propsFile"
		props=script.readProperties text: "$textProps"				
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
				
			script.powershell '''
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
}