/*
* 	Name: 		updateAssemblyInfo
*	Purpose: 	Update all assemblyinfo.cs files under the given path with the specified version strings
*	Parameters:	
				newVersion - String to use for the AssemblyVersion and AssemblyFileVersion parameters
				newInfoVersion - String to use for the AssemblyInformationalVersion parameter
				versionPath - Path under which to recursively update assemblyinfo.cs files
	Keith Douglas
	Dec 2017
	keith.douglas@retailinmotion.com
*/
def call (body) {
	def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
	
	echo "Updating assembly info at ${config.versionPath} to ${config.newVersion} / ${config.newInfoVersion}"
	
	withEnv(["assemblyVersion=${config.newVersion}",
			"assemblyInfoVersion=${config.newInfoVersion}", 
			"versionPath=${config.versionPath}"]) {
			
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