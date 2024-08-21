package com.retailinmotion;

import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonSlurperClassic
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

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
def getGitVersionInfo(dockerImageOrToolPath, dockerContext=null, subPath =null, changeBranch=null){
	def args
	def gitVersionExe
	def useTool=false
	def useDocker=false
	
	def osType=checkOs()
	
	if (dockerContext != null){
		useTool=false
		useDocker=true
		echo "Using gitversion docker image ${dockerImageOrToolPath} on ${osType}"
	} else {
		useTool=true
		useDocker=false
		// Check the path if we are using the tool
		if (osType == "windows" && !dockerImageOrToolPath.toString().endsWith(".exe")){
			gitVersionExe=new File(dockerImageOrToolPath, "GitVersion.exe") 
		} else if (osType == "macos"){
			gitVersionExe=new File(dockerImageOrToolPath, "GitVersion") 
			// Need to make sure it's a unix path separator due to https://issues.jenkins-ci.org/browse/JENKINS-36791
			gitVersionExe=gitVersionExe.toString().replaceAll("\\\\", "/")
		} else {
			gitVersionExe= new File(dockerImageOrToolPath)
			// Need to make sure it's a unix path separator due to https://issues.jenkins-ci.org/browse/JENKINS-36791
			gitVersionExe=gitVersionExe.toString().replaceAll("\\\\", "/")
		}
		echo "Using gitversionexe at ${gitVersionExe} for ${osType}"
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
		if (osType == "windows"){
			withEnv(["gitVersionExe=${gitVersionExe}", "subPath=${subPath}"]) {
				powershell '''
				&"$($env:gitVersionExe)" "$($env:WORKSPACE)$($env:subPath)" | Out-File gitversion.txt -Encoding ASCII -Force
				If($LASTEXITCODE -ne 0){ 
					Get-Content gitversion.txt
				}
				'''
			}
		} else {
			withEnv(["gitVersionExe=${gitVersionExe}", "subPath=${subPath}"]) {
				sh '''
				"${gitVersionExe}" "${WORKSPACE}${subPath}" > gitversion.txt
				if [ $? -ne 0 ]; then cat gitversion.txt; fi
				'''
			}
		}
	} else if (useDocker){
		// Execute the command inside the given docker image (intended for use on linux systems)
		// when using gitversion4, this was done using Mono to call the .exe file
		// In gitversion5, dotnet core is used instead
		//usr/local/bin/gitversion /src${subPath} > gitversion.txt
		dockerContext.image(dockerImageOrToolPath).inside("-v \"$WORKSPACE:/src\" -e subPath=\"$subPath\" -e args=\"$args\" -e IGNORE_NORMALISATION_GIT_HEAD_MOVE=1"){
			sh '''
				if [ -e /usr/lib/GitVersion/tools/GitVersion.exe ]; 
				then 
					mono /usr/lib/GitVersion/tools/GitVersion.exe /src${subPath} > gitversion.txt
				elif [ -e /usr/local/bin/gitversion ]; 
				then 
					ls -ltr /src
					sh /usr/local/bin/gitversion /src${subPath} > gitversion.txt
				else
					/usr/bin/dotnet /app/GitVersion.dll /src${subPath} > gitversion.txt
				fi
				if [ $? -ne 0 ]; then cat gitversion.txt; fi
			'''
		}
	} else {
		echo "Both useTool and useDocker are false, this shouldn't be possible, something has gone wrong in getGitVersionInfo"
		exit 1
	}
	
	def output = readFile(file:'gitversion.txt')
	
	return parseGitVersionInfo(output, changeBranch)
}

/*
* Parse the output from gitversion
* This is mainly used by getGitVersionInfo but can optionally be called separately if needed
*/
def parseGitVersionInfo(output, changeBranch=null){
	def json = new JsonSlurperClassic().parseText(output)
	
	// If this was a Pull Request branch, we lose a lot of information in the version string
	// So the changeBranch argument allows passing in the original branch path (e.g. feature/JIRA-1234-dosomething), which we can then add to the version info
	if (changeBranch != null && changeBranch != "") {
      def jsoncopy = json.getClass().newInstance(json) // make a copy to use for the iterator 
      preReleaseLabel=json.PreReleaseLabel
	  prBranch=json.BranchName
      branchName=changeBranch.substring(changeBranch.indexOf("/")+1, changeBranch.length()) + ".${prBranch}"
      jsoncopy.each { key, value ->
        	def newvalue=value.toString().replace(preReleaseLabel, branchName)
  			json."$key"=newvalue
		}
	}
	
	// Add a helm-safe version for strings which can contain a + symbol
	// This is due to https://github.com/helm/helm/issues/1698
	// Not all charts (private and public) are calling replace when referencing .Chart.Version,
	// so make it available here for use to avoid deploy time issues
	json.SafeFullSemVer=json.FullSemVer.toString().replaceAll("\\+", "-").replaceAll("/", "-").replaceAll("\\\\", "-").replaceAll("_", "-")
	// The default informational version can be very long for feature branches, 
	// so make a copy of the original as FullInformationalVersion and create a new version with a shorter format	
	json.FullInformationalVersion=json.InformationalVersion
	
	// Replace the long git hash with the short version, and if the build metadata portion repeats the prereleaselabel, remove it
	def gitHashes=getGitHashes()
	def preReleaseLabel=json.PreReleaseLabel
	if (gitHashes.short =~ /^0\d+$/){
		error("The short SHA for this git commit has a leading zero followed only by digits (${gitHashes.short}) - this will error later when pushing to Octopus due to failing the semver check. \nTo fix this, do another commit so that the SHA changes. More details under DEVOPS-452")
	}
	json.InformationalVersion=json.InformationalVersion.replace(gitHashes.full, gitHashes.short).replaceAll("_", "-").replace("/${preReleaseLabel}", "")
	
	// If the full branch name is too long, it can cause issues when octopus unpacks the archive due to path length restrictions in windows
	// If this is a branch which references a JIRA VECTWO ticket, shorten the prereleaselabel to just the ticket number without any other decoration to keep the package name short

	// DEVOPSREQ-394 : Refactoring to include JIRA POSCON and VREC tickets
	// DEVOPSREQ-1764 : Refactoring to include JIRA SYS tickets
	def issueType = ["VECTWO","POSCON","VREC","SYS"]
	for (issueTypeItem in issueType){
  		if (preReleaseLabel.contains(issueTypeItem)) {
			def jiraRef=(preReleaseLabel =~ /($issueTypeItem\-{1}[0-9]*)(.*)/)
			json.InformationalVersion=json.InformationalVersion.replace(preReleaseLabel, jiraRef[0][1])
		}
	}
	
	// and if the branchname contains a VECTWO reference with a long name, shorten it to just the ticket number in the informational version
	// DEVOPSREQ-394 : Refactoring to include JIRA POSCON and VREC tickets
	for (issueTypeItem in issueType){
  		if (json.BranchName.contains(issueTypeItem)) {
			def match=(json.BranchName =~ /($issueTypeItem\-{1}[0-9]*)(.*)/)
			json.InformationalVersion=json.InformationalVersion.replaceAll(/($issueTypeItem\-{1}[0-9]*)([A-Za-z0-9\-\_]*)/, match[0][1])
		}
	}

	// Keep a copy of the informational version with unsafe characters replaced
	json.SafeInformationalVersion=json.InformationalVersion.toString().replaceAll("\\+", "-").replaceAll("/", "-").replaceAll("\\\\", "-").replaceAll("_", "-")
	
	// Since we are changing the tag in gitversion.yml for some repos, parse the prerelease label from the branchname 
	if(json.BranchName.contains("/") && env.BRANCH_NAME.indexOf("/") > 0){
			json.PackagePreRelease=json.BranchName.substring(0, env.BRANCH_NAME.indexOf("/"))
	} else {
		json.PackagePreRelease=json.BranchName
	}

	return json
}
/*
* Check what OS the script is executing on
*/
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
  if ( version != null ){
	dockerContext.image(helmImage).inside("-e targetDir=\"$targetDir\" -e srcDir=\"$srcDir\" -e chartName=\"$chartName\" -e version=\"$version\"" ) { 
		sh '''
			mkdir -p $targetDir/$chartName
			sed -i s/"  tag: latest"/"  tag: $version"/ig "$srcDir/$chartName/values.yaml"
			helm lint $srcDir/$chartName
			helm package --version $version --app-version $version -d $targetDir/$chartName $srcDir/$chartName
		'''
		
	}
  } else {
    dockerContext.image(helmImage).inside("-e targetDir=\"$targetDir\" -e srcDir=\"$srcDir\" -e chartName=\"$chartName\" -e version=\"$version\"" ) { 
		sh '''
			mkdir -p $targetDir/$chartName
			helm lint $srcDir/$chartName
			helm package -d $targetDir/$chartName $srcDir/$chartName
		'''
		
	}
  }
}

/*
*	Name: 		fetchHelmChart
*	Purpose: 	Uses helm builder docker image to fetch a given helm chart locally and rename it with the given name
*	
*	Parameters:
				helmRepo		- URL for target helm repository
				chartName		- Name of chart to fetch
				targetDir		- Where to output downloaded chart content
				newChartName	- New name of chart - used to rename it
				valueFile		- Path of values file to copy in the newly fetched package
				dockerContext	- docker variable from jenkins pipeline
				helmImage		- Name of docker image to use containing helm and kubectl executables
				chartVersion	- Version of chart to fetch
*/
def fetchHelmChart(helmRepo, chartName, targetDir, newChartName, valuesFile, dockerContext, helmImage, chartVersion=""){
	echo "Fetching $chartName from $helmRepo"
	def version=""
	if (chartVersion && chartVersion != "") {
		version="--version $chartVersion"
		dockerContext.image(helmImage).inside("-e HELMREPO=$helmRepo -e targetDir=\"$targetDir\" -e chartName=\"$chartName\" -e newChartName=\"$newChartName\" -e valuesFile=\"$valuesFile\" -e version=\"$version\"" ) { 
			sh '''
				helm repo add nexus $HELMREPO
				helm fetch nexus/$chartName --untar --untardir $targetDir $version
				mv $targetDir/$chartName $targetDir/$newChartName
				sed -i "s/name: $chartName/name: $newChartName/ig" "$targetDir/$newChartName/Chart.yaml"
				cp $valuesFile "$targetDir/$newChartName/values.yaml"
			'''
		}
	}
	else {
		dockerContext.image(helmImage).inside("-e HELMREPO=$helmRepo -e targetDir=\"$targetDir\" -e chartName=\"$chartName\" -e newChartName=\"$newChartName\" -e valuesFile=\"$valuesFile\"" ) { 
			sh '''
				helm repo add nexus $HELMREPO
				helm fetch nexus/$chartName --untar --untardir $targetDir
				mv $targetDir/$chartName $targetDir/$newChartName
				sed -i "s/name: $chartName/name: $newChartName/ig" "$targetDir/$newChartName/Chart.yaml"
				cp $valuesFile "$targetDir/$newChartName/values.yaml"
			'''
		}
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
            for file in $(find $chartDir -name *.tgz)
            do
                chartFile=$(basename $file)
                curl -s -u $repoAuth --upload-file $file $HELMREPO/$chartFile
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

// Generate a package suffix using buildInfo and git hashes
def getPackageSuffix (buildInfo, gitHashes){
	def packageSuffix
	if ( buildInfo.buildType == 'release') {
		packageSuffix="-${buildInfo.buildType}+Branch.${buildInfo.longFeatureName}.Sha.${gitHashes.short}".replaceAll(/[^0-9A-Za-z-\.\+]/, "");
	} else {
		packageSuffix="-${buildInfo.buildType}+Branch.${buildInfo.featureName}.Sha.${gitHashes.short}".replaceAll(/[^0-9A-Za-z-\.\+]/, "");
	}
	
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
  	echo "getChangeString no longer returns data, configure release notes template in Octopus instead."
	return ""
}

/*
* Get changes as a string, re-implements original functionality of getChangeString for use with builds that are not using octopus metadata (e.g. helm charts)
*/
def getChanges() {
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
	
	changeString =""

	return changeString
}

/*
*Get Git commit info that is not yet available in jenkins pipeline
 */
def getLastCommitAuthor( getEmail = null ){
	def filter = 'an'
	if (getEmail) {
		filter = 'ae'
	}
	if(isUnix()){
		gitCommitter = sh(returnStdout: true, script: """
	git show -s --pretty=%${filter}
	""").trim()
	} else {
		gitCommitter = powershell(returnStdout: true, script: """
	git show -s --pretty=%${filter}
	""").trim()
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
def sendNotifications( buildStatus, tokenCredentialId, commitAuthor, customMessage = null, attachments = new JSONArray() ){
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

	slackSend (color: colorCode, message: message, tokenCredentialId: tokenCredentialId, attachments: attachments.toString() )

}

/*
* Given an array of fields, populate a JSON array of objects,
* which can be passed to sendNotifications to create an attachent in the slack notification
*
*/

def generateSlackAttachments(fields){
	JSONObject attachment = new JSONObject();
	JSONArray attachments = new JSONArray();
	attachment.put('author',"jenkins");
	attachment.put('author_link', env.BUILD_URL);
	attachment.put('title', env.JOB_NAME);
	attachment.put('fallback', "Build output for ${env.JOB_NAME} Build #${env.BUILD_NUMBER}");
	attachment.put('mrkdwn_in', ["fields"])
	
	def fieldsArray=[]
	fields.each{ field ->
		def sidebyside=false
		if(field.short != null){
			sidebyside=field.short
		}
		JSONObject attachmentObject = new JSONObject();
		attachmentObject.put('title', field.title)
		attachmentObject.put('value', field.value)
		attachmentObject.put('short', sidebyside)
		fieldsArray.add(attachmentObject)
	}
	
	attachment.put('fields', fieldsArray);
	attachments.add(attachment);
	
	return attachments;
}

/*
* Upload file to Nexus repository
* Purpose: 	Upload file to Nexus repository from jenkins pipelines
*	Parameters:
* 				filePath - String containing the path to the file
* 				contentType - content type for the file being uploaded 
*							- eg application/xml for plain xml or pom, application/java-archive for .aar or .jar files
*				nexusUrl - String representing the final url of the file that will be uploaded
*				nexusUsername - String containing the Nexus username
*				nexusPassword - String containing the corresponding Nexus password
* 				os - String, should be linux, macos or windows
*/
def uploadFileToNexus(filePath, contentType, nexusUrl, nexusUsername, nexusPassword, os){
	def exists = fileExists filePath
	if (exists){
		echo "File to upload: ${filePath}"
	}
	else{
		error("Could not locate the requested file at '${filePath}'")
	}
	echo "Preparing Nexus upload at ${nexusUrl}"
	def uploadStatus
	os = os.toLowerCase()
	if (os == 'linux' || os == 'macos'){
		def shScript = """
		if curl -u ${nexusUsername}:${nexusPassword} --output /dev/null --silent --head --fail "${nexusUrl}" ;then
			echo "ERROR: An artifact already exists at ${nexusUrl} , will not overwrite it."
			exit 1
		else
			echo "File does not exist, uploading"
			curl -s -S -u ${nexusUsername}:${nexusPassword} --upload-file ${filePath} ${nexusUrl}
			if curl -u ${nexusUsername}:${nexusPassword} --output /dev/null --silent --head --fail "${nexusUrl}" ;then
				echo "File uploaded successfully"
				exit 0
			else
				echo "ERROR: File not uploaded"
				exit 2
			fi
		fi
		"""
		// Checking if the file exists because Curl does not return upload errors in this case
		uploadStatus = sh(returnStatus: true, script: shScript)
	}
	else if (os == 'windows'){
		def psScript = """
		\$ErrorActionPreference = 'Stop'
		\$secpasswd = ConvertTo-SecureString '${nexusPassword}' -AsPlainText -Force
		\$mycreds = New-Object System.Management.Automation.PSCredential ('${nexusUsername}', \$secpasswd)
		#Check if artifact already exists#
		try{
			\$result = Invoke-RestMethod -Method Head -Uri ${nexusUrl} -Credential \$myCreds
		}
		catch{
			Write-Host "File does not exist, uploading"
		}
		if (\$null -ne \$result -or \$result.StatusCode -eq 200){
			Write-Error "ERROR: An artifact already exists at ${nexusUrl} , will not overwrite it."
		}
		Invoke-RestMethod -Method Put -Uri ${nexusUrl} -InFile ${filePath} -Credential \$myCreds -ContentType "${contentType}"
		"""
		// Not using curl because it does not return errors
		uploadStatus = powershell(returnStatus: true, script: psScript)
	}
	else {
		error("Unknown OS: ${os}; could not upload the file.")
	}
	echo "Upload status: ${uploadStatus}"

	if (uploadStatus != 0) {
		error("Could not upload file to Nexus - see above error!")
	}
	else{
		echo "Nexus upload successful"
	}
}

/*
* Scan a Terraform repo with TFSec
* Output handling assumes the default human readable output with --no-colour
* If --soft-fail is not specified, finding any vulnerabilities will result in an immediate build fail
* Built using TFSec docker image version 0.58.6
* Parameters:
* 		pathToScan - String containing the path to the Terraform repo you want to scan (most likely env.WORKSPACE)
*		dockerImage - String containing full repo path and version for the docker image (eg ${dockerPullRim}/tfsec/tfsec:latest)
*		tfsecArgs - String containing additional space-separated TFSec arguments to pass
*/
def runTfsec(pathToScan, dockerImage, tfsecArgs = "--soft-fail --no-colour"){
	tfsecArgs = tfsecArgs.trim()
	if (tfsecArgs != null && tfsecArgs != ""){
		tfsecArgs = " " + tfsecArgs.trim()
	}
	def tfsecOut = sh(returnStdout: true, script: "docker run --rm -v ${pathToScan}:/src -w /src ${dockerImage} .${tfsecArgs}").trim()
	echo "---- TFSec results ----"
	echo tfsecOut
	def tfsecIssues = tfsecOut.substring(tfsecOut.lastIndexOf("\n")).trim()
	if (tfsecIssues.indexOf("No problems detected!") == -1){
		tfsecIssues = tfsecIssues.replace(" potential problems detected.","").trim()
		error("Tfsec detected ${tfsecIssues} vulnerabilities - check the above output and fix them!")
	}
}