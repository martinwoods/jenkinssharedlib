Jenkins Shared Libraries

These libraries are available to Jenkins pipeline builds and provide 'helper' functions which are common across multiple build jobs. 
There are two helpers currently available;

[buildHelper.groovy](http://bitbucket.rim.local:7990/projects/DEVOPS/repos/jenkinssharedlibraries/browse/src/com/retailinmotion/buildHelper.groovy) - This file provides general build helper functions, e.g. for getting version information using gitVersion, packaging helm charts, updating AssemblyInfo.cs files etc..

[octopusHelper.groovy](http://bitbucket.rim.local:7990/projects/DEVOPS/repos/jenkinssharedlibraries/browse/src/com/retailinmotion/OctopusHelper.groovy) - This helper provides functions for interacting with the Octopus Deploy server, such as uploading packages, creating releases etc..

Information on using Jenkins shared libraries can be found in the [Jenkins Docs](https://jenkins.io/doc/book/pipeline/shared-libraries/)

Examples on loading and using these libraries can be seen in many Jenkinsfile's used across Retail inMotion projects, e.g. Backoffice components such as [vEsb](http://bitbucket.rim.local:7990/projects/VESB/repos/vectoresb/browse/Jenkinsfile#3,36,49,56,151-154,156,168) or Microservices projects like [DutyPlan](http://bitbucket.rim.local:7990/projects/MCAB/repos/dutyplan/browse/build/Jenkinsfile#3-4,90,95,129,138,148,178)

## How to Set up a migrated vector cloud API jenkins pipeline

[Jenkins pipeline for migrated vector cloud Apis](https://knowledge.retailinmotion.com/display/VX/Jenkins+pipeline+for+migrated+vector+cloud+Apis)