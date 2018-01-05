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
	def packageString="${assemblyInfo.Major}.${assemblyInfo.Minor}.$buildNumber-${buildInfo.buildType}+Branch.${buildInfo.featureName}.Sha.${gitHashes.full}".replaceAll(/[^0-9A-Za-z-\.\+]/, "");
	
	return packageString
}
