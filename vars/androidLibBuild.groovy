def call () {
    def buildHelper = new com.retailinmotion.buildHelper()
    def octopusHelper = new com.retailinmotion.OctopusHelper()
    def versionInfo
    def libraryName
    def filePath
    def nexusUploadUrl
    def os
    def originalBranchName
    def pomPath = 'build.pom'
    def pomContent = "<project>\n\t<modelVersion>4.0.0</modelVersion>\n\t<groupId>com.retailinmotion</groupId>\n\t<artifactId>LIBNAME_HERE</artifactId>\n\t<version>LIBVER_HERE</version>\n\t<type>aar</type>\n</project>"

    pipeline {
        agent {label 'androidsdk'}
        options {
            skipDefaultCheckout()
            timestamps()
        }
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
                        bat './gradlew jacocoTestReport'
                        bat """./gradlew.bat --info sonarqube assembleRelease ^
                                -Dsonar.projectKey=${libraryName} -Dsonar.branch.name=${originalBranchName} ^
                                -Dsonar.projectVersion=${versionInfo.FullSemVer} -Dsonar.projectName=${libraryName}
                            """
                    }
                }
            }

            stage('Upload to Nexus'){
                steps{
                    withCredentials([usernamePassword(credentialsId: 'jenkins-nexus.retailinmotion.com-docker', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        script{
                            pomContent = pomContent.replace('LIBNAME_HERE', "${libraryName}").replace('LIBVER_HERE', "${versionInfo.FullSemVer}")
                            writeFile(file: pomPath, text: pomContent)
                            def aarFiles = findFiles(glob: '**/*.aar')
                            echo "AarFiles: ${aarFiles}"
                            filePath = aarFiles[0].path

                            nexusAarUrl = "${env.RiMMavenRelease}com/retailinmotion/${libraryName}/${versionInfo.SafeInformationalVersion}/${libraryName}-${versionInfo.SafeInformationalVersion}.aar"
                            buildHelper.uploadFileToNexus(filePath, 'application/java-archive', nexusAarUrl, "$USERNAME", "$PASSWORD", os)

                            nexusPomUrl = "${env.RiMMavenRelease}com/retailinmotion/${libraryName}/${versionInfo.SafeInformationalVersion}/${libraryName}-${versionInfo.SafeInformationalVersion}.pom"
                            buildHelper.uploadFileToNexus(pomPath, 'application/xml', nexusPomUrl, "$USERNAME", "$PASSWORD", os)
                        }
                    }
                }
            }
/*             stage('Clean Workspace'){
                steps {
                    cleanWs()
                }
            } */
        }
    }

}
