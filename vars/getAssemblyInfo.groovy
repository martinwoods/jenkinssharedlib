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
def call (body) {
	def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
	
	
	echo "Reading assembly version info from ${config.filePath}"
	
	// Store the info in a timestamped file so that we don't clash with other build files
	Date d=new Date();
	def propsFile="versioninfo." + d.getTime() + ".properties"
	
	withEnv(["filePath=${config.filePath}", "propsFile=$propsFile"]) {
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