def call () {
    def buildHelper = new com.retailinmotion.buildHelper()
    def octopusHelper = new com.retailinmotion.OctopusHelper()
    def versionInfo
    def libraryName
    def filePath
    def nexusUploadUrl
    def os
    def originalBranchName

    pipeline {
        agent {label 'androidsdk'}
        options { skipDefaultCheckout() }
        stages {
            stage('Clean and checkout'){
                steps {
                    cleanWs()
                    checkout scm
                }
            }
            stage('Get Version'){
                steps {
                    script {
                        def gitVersionTool=tool name: 'GitVersion-5', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
                        versionInfo=buildHelper.getGitVersionInfo(gitVersionTool)
                        echo "Version Info:"
                        echo versionInfo.toString()
                        // Update jenkins build name
                        currentBuild.displayName = "#${versionInfo.FullSemVer}"
                        currentBuild.description = "${versionInfo.InformationalVersion}"
                    }
                }
            }
            stage('Get repo info'){
                steps{
                    script{
                        os = octopusHelper.checkOs()
                        if (os == 'linux' || os == 'macos'){
                            libraryName = sh(returnStdout: true, script: 'basename `git remote get-url origin` .git').trim()
                        }
                        else if (os == 'windows'){
                            libraryName = powershell(returnStdout: true, script: 'Write-Output ((Split-Path (& git remote get-url origin) -Leaf).replace(".git",""))').trim()
                        }
                        echo "Detected library name: ${libraryName}"
                        originalBranchName = "$env.CHANGE_BRANCH" != "null" ? "$env.CHANGE_BRANCH" : "$env.BRANCH_NAME"
                    }
                }
            }
            stage('Build'){
                steps {
                    script {
                        scannerHome = tool 'SonarScannerRiM'
                    }
                    withSonarQubeEnv('SonarQubeServer') {
                        bat './gradlew.bat cleanBuildCache'
                        bat """./gradlew.bat sonarqube assembleRelease ^
                                -Dsonar.projectKey=${libraryName} -Dsonar.branch.name=${originalBranchName}^
                                -Dsonar.projectVersion=${versionInfo.FullSemVer} -Dsonar.projectName=${libraryName}
                            """
                    }
                }
            }

            stage('Upload to Nexus'){
                steps{
                    withCredentials([usernamePassword(credentialsId: 'jenkins-nexus.retailinmotion.com-docker', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        script{
                            def aarFiles = findFiles(glob: '**/*.aar')
                            echo "AarFiles: ${aarFiles}"
                            filePath = aarFiles[0].path
                            def exists = fileExists filePath
                            if (exists){
                                echo "Build artifact: ${filePath}"
                            }
                            else{
                                error("Could not locate the build output at '${filePath}'")
                            }
                            nexusUploadUrl = "${env.RiMMavenRelease}com/retailinmotion/${libraryName}/${versionInfo.SafeInformationalVersion}/${libraryName}-${versionInfo.SafeInformationalVersion}.aar"
                            echo "Preparing Nexus upload at ${nexusUploadUrl}"
                            def uploadStatus
                            if (os == 'linux' || os == 'macos'){
                                uploadStatus = sh(returnStatus: true, script: "curl.exe -s -S -u $USERNAME:$PASSWORD --upload-file ${filePath} ${nexusUploadUrl}")
                            }
                            else if (os == 'windows'){
                                def psScript = """
                                \$ErrorActionPreference = 'Stop'
                                \$secpasswd = ConvertTo-SecureString '$PASSWORD' -AsPlainText -Force
                                \$mycreds = New-Object System.Management.Automation.PSCredential ('$USERNAME', \$secpasswd)
                                #Check if artifact already exists#
                                try{
                                    \$result = Invoke-RestMethod -Method Head -Uri ${nexusUploadUrl} -Credential \$myCreds
                                }
                                catch{
                                    Write-Host "File does not exist, will upload"
                                }
                                if (\$null -ne \$result -or \$result.StatusCode -eq 200){
                                    Write-Error "An artifact already exists at ${nexusUploadUrl} , will not overwrite it."
                                }
                                Invoke-RestMethod -Method Put -Uri ${nexusUploadUrl} -InFile ${filePath} -Credential \$myCreds -ContentType "application/java-archive"
                                """
                                //not using curl because it does not reliably return errors when running in PS
                                uploadStatus = powershell(returnStatus: true, script: psScript)
                            }
                            echo "Status: ${uploadStatus}"

                            if (uploadStatus != 0) {
                                error("Could not upload library to Nexus - see above curl error!")
                            }
                            else{
                                echo "Nexus upload successful"
                            }
                        }
                    }
                }
            }
            stage('Clean Workspace'){
                steps {
                    cleanWs()
                }
            }
        }
    }

}