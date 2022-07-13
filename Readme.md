Jenkins Shared Libraries

These libraries are available to Jenkins pipeline builds and provide 'helper' functions which are common across multiple build jobs. 
There are two helpers currently available;

[buildHelper.groovy](http://bitbucket.rim.local:7990/projects/DEVOPS/repos/jenkinssharedlibraries/browse/src/com/retailinmotion/buildHelper.groovy) - This file provides general build helper functions, e.g. for getting version information using gitVersion, packaging helm charts, updating AssemblyInfo.cs files etc..

[octopusHelper.groovy](http://bitbucket.rim.local:7990/projects/DEVOPS/repos/jenkinssharedlibraries/browse/src/com/retailinmotion/OctopusHelper.groovy) - This helper provides functions for interacting with the Octopus Deploy server, such as uploading packages, creating releases etc..

Information on using Jenkins shared libraries can be found in the [Jenkins Docs](https://jenkins.io/doc/book/pipeline/shared-libraries/)

Examples on loading and using these libraries can be seen in many Jenkinsfile's used across Retail inMotion projects, e.g. Backoffice components such as [vEsb](http://bitbucket.rim.local:7990/projects/VESB/repos/vectoresb/browse/Jenkinsfile#3,36,49,56,151-154,156,168) or Microservices projects like [DutyPlan](http://bitbucket.rim.local:7990/projects/MCAB/repos/dutyplan/browse/build/Jenkinsfile#3-4,90,95,129,138,148,178)

## How to Set up a vector 2.5 jenkins pipeline

The groovy function 
[dotnetBackendVectorCloudBuild.groovy](http://bitbucket.rim.local:7990/projects/DEVOPS/repos/jenkinssharedlibraries/browse/src/com/retailinmotion/dotnetBackendVectorCloudBuild.groovy) allows migrated vector 2.5 apis to run a jenkins pipeline and to be deployed on the vector 2 iis through octopus.

* This function only works for migrated apis running under .net6

* This pipeline is made to run only on windows jenkins servers, for more info: [Jenkins Info Infrastructure](https://knowledge.retailinmotion.com/display/DEVOPS/Jenkins+Build+Infrastructure)


**❗❗ In order to make it possible, the csproj files from the application must include the following configurarion under the `Property Group` tag, with the only exception being the unit test projects:**

```csharp
<RuntimeIdentifiers>win-x64</RuntimeIdentifiers>
```

* On your root project folder, you'll need to do as the following in order to setup the jenkins run:
  * Create on your root project folder a folder called `build`
  * Under the `build` folder, create a file called `Jenkinsfile`
  * Inside the file we have some values that will be passed as args to a groovy function (which is the pipeline script)

```csharp
#!groovy
// Import the shared lib containing the build pipeline
library "vectorShared"

def buildParams = [:]
buildParams.appName = "same name as the octopus"
buildParams.projectBuildPath = "csproj path from the root folder" 
buildParams.unitTestPath = "tests/Tests.Unit/Tests.Unit.csproj" // tests/Tests.Unit/Tests.Unit.csproj
buildParams.enableValidation = "0"
buildParams.deployToOctopusSandbox = "1"
buildParams.noTestsOnBranches = [ "master" ]
buildParams.slackTokenId = 'slack-notification-tm_foundations_build'

// Run the build pipeline function
dotnetBackendVectorCloudBuild(buildParams)

```

#### here is a simple example and explanation of the values to be passed:
```csharp
 
appName = "vector-cloud-{someName}"
projectBuildPath = "src/projectName/cprojFile"
unitTestPath = "tests/Tests.Unit/Tests.Unit.csproj" // it can be empty as long as the enable validation is "0"
enableValidation = "1" // when enable is "1" it will run the sonarqube scan across the project and also run the unit tests, otherwise should be "0"
deployToOctopusSandbox =  "1" // if it is set to "1" it will publish changes to octopus sandbox and then a sync to live has to be made, otherwise should be "0"
noTestsOnBranches = [ "master" ] // branch that the sonarqube and unit tests will not be performed
slackTokenId = 'slack-notification-{slackChannel}'
```

After seeting up the build files within your project you need to:

* Create a jenkins project
  * It can be achieved by clonning an existing project
  * Go to [Jenkins](http://rimdub-jen-01.rim.local:8080/)
  * Click on `New Item` on your top lef hand side
  * At the very bottom type the project you want to copy from, i.e.: `vector-cloud-schedule`
  * Make sure the `Branch Sources` section under the `Configuration` menu, the `Owner` and the `Repository Name` are set correctly, as well as some other items such as `Descriptions` and etc.
* Create an octopus project
  * Under `Process` follow the same steps the `vector-cloud-schedule` has

