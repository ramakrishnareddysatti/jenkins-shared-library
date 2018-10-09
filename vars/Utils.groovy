/* ################################  API Utility Methods ############################### */
// Using in Common Application Jenkins file
def getReleasedVersion(applicationDir) {
	def matcher = readFile("${applicationDir}/pom.xml") =~ '<version>(.+?)</version>'
	matcher ? matcher[0][1] : null
}

// Using in DP and PC API Application Jenkins file
def getArtifact(applicationDir) {
	def matcher = readFile("${applicationDir}/pom.xml") =~ '<artifactId>(.+?)</artifactId>'
	matcher ? matcher[0][1] : null
}

def sonarScanner(applicationDir, releasedVersion){
	//********* Configure a webhook in your SonarQube server pointing to <your Jenkins instance>/sonarqube-webhook/ ********
	dir(applicationDir) {
			withSonarQubeEnv('SonarQube_V7') { // SonarQube taskId is automatically attached to the pipeline context
				sh "mvn org.sonarsource.scanner.maven:sonar-maven-plugin:3.3.0.603:sonar -Drevision=${releasedVersion}" }
		}
}

def processQualityGate() {
	// Just in case something goes wrong, pipeline will be killed after a timeout
	timeout(time: 2, unit: 'MINUTES') {
		def qualityGate = waitForQualityGate() // Reuse taskId previously collected by withSonarQubeEnv
		if (qualityGate.status != 'OK') {
			//error "Pipeline aborted due to quality gate failure: ${qualityGate.status}"
			println("SonarQube Quality Gate Failed.failure: ${qualityGate.status}")
		} else
		{
			println("SonarQube Quality Gate Passed")
		}
	}
}

def processCodeCoverage(applicationDir, releasedVersion) {
	dir(applicationDir) {
			sh  "mvn cobertura:cobertura -Dcobertura.report.format=xml -Drevision=${releasedVersion}"
			cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: '**/target/site/cobertura/*.xml',
			failNoReports: false, failUnhealthy: false, failUnstable: false,  maxNumberOfBuilds: 0,
			onlyStable: false, sourceEncoding: 'ASCII', zoomCoverageChart: false}
}
def tagBranch(applicationDir, repoUrl, taggedVersion) {
	sshagent (credentials: ['global-shared-library']) {
		dir (applicationDir) {
			sh "ls -l"
			sh "git remote set-url origin ${repoUrl}"
			//sh "git tag ${IMAGE_BRANCH_PREFIX}-${BUILD_NUMBER}"
			sh "git tag ${taggedVersion}"
			sh "git push --tags"
		}
	}
}

def sourceCodeCheckout(applicationDir, branchName, repoUrl) {
	deleteDir()
	echo "Checkout in progress..."
	dir(applicationDir) {
		git branch: "${branchName}",
		credentialsId: 'global-shared-library',
		url: "${repoUrl}"
	}
}

/*
 * Responsible to remove "dangling images" and application "snapshot" images (if exists).
 *
 */
def removeDanglingImages(artifactName, serverIP, serviceAccount) {
	try{
		sh """
			ssh -i  ~/.ssh/id_rsa ${serviceAccount}@${serverIP} 'docker images --no-trunc -aqf dangling=true | xargs --no-run-if-empty docker rmi && 
			docker images | grep ${artifactName} | tr -s " " | cut -d " " -f 3 | xargs --no-run-if-empty docker rmi' 
			"""	
	} catch(error) {
		echo "${error}"
	}
}

/*
 * Responsible to remove "dangling images" and application "snapshot" images (if exists) from JENKINS BOX.
 */ 
def removeImages(artifactName) {
	// docker images | grep snapshot | tr -s " " | cut -d " " -f 3 | xargs --no-run-if-empty docker rmi -f
	try{
		sh """
			docker images --no-trunc -aqf dangling=true | xargs --no-run-if-empty docker rmi && 
			docker images | grep ${artifactName} | tr -s " " | cut -d " " -f 3 | xargs --no-run-if-empty docker rmi -f
		"""
	} catch(error) {
		echo "${error}"
	}
}


def pushImage(artifactName, releasedVersion, dockerRegistryIP) {
	
	echo "pushImage: artifactName: ${artifactName}"
	echo "pushImage: releasedVersion: ${releasedVersion}"

	sh """
		docker tag ${artifactName}:${releasedVersion} ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}
		docker push ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}
	"""
}


/*
 * Stop and Remove Container (if exists)
 */
def stopContainer(artifactName, serverIP, serviceAccount) {
	try{
	
		sh """
			ssh -i  ~/.ssh/id_rsa ${serviceAccount}@${serverIP} 'docker ps --no-trunc -aqf \'name=${artifactName}\' | xargs -I {} docker stop {} &&
			docker ps --no-trunc -aqf \'name=${artifactName}\' | xargs -I {} docker rm {}' 
			"""	
	} catch(error) {
		echo "${error}"
	}
}

/*Demand Planner API Configuration */
def promoteDPAPIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP, dockerRegistryIP, serviceAccount) {
		sh """
				ssh -i  ~/.ssh/id_rsa ${serviceAccount}@${serverIP} 'docker run -e \'SPRING_PROFILES_ACTIVE=${PROP_ENV}\' -v /local/mnt:/local/mnt -d -p 8099:8090 --name ${artifactName} ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}'
				"""
}

/*Promote Priority Configuration */
def promotePCAPIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP, dockerRegistryIP, serviceAccount) {
		sh """
				ssh -i  ~/.ssh/id_rsa ${serviceAccount}@${serverIP} 'docker run -e \'SPRING_PROFILES_ACTIVE=${PROP_ENV}\' -v /local/mnt:/local/mnt -d -p 8091:8091 --name ${artifactName} ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}'
				"""
}

def pullDockerImage(artifactName, releasedVersion, serverIP, dockerRegistryIP, serviceAccount) {
		sh """
				ssh -i  ~/.ssh/id_rsa ${serviceAccount}@${serverIP} 'docker pull ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}'
				"""
}
/* ################################  UI Utility Methods ############################### */

//This stage installs all of the node dependencies, performs linting and builds the code.
def npmBuild(applicationDir, branchName, repoUrl) {
	dir(applicationDir) {
		git branch: "${branchName}",
		credentialsId: 'global-shared-library',
		url: "${repoUrl}"

		//node --version
		//npm --version
		sh '''
				npm install -g npm@5.6.0 @angular/cli@~1.7.3
				npm install
				ng build --prod --aot
			'''
	}
}

def apiDockerBuild(applicationDir, artifactName, releasedVersion) {
	dir(applicationDir) {
		echo "Starting Docker Image Creation..."
		// Build argument 'jar_file' defined in demandplannerapi Dockerfile.
		sh "docker build --build-arg jar_file=target/${artifactName}-${releasedVersion}.jar -t ${artifactName}:${releasedVersion} ."
		echo "Docker Image Creation Complted..."
	}
	sh "docker images"
}

def uiCodeQualityAnalysis(applicationDir, releaseVersion) {
	//********* Configure a webhook in your SonarQube server pointing to <your Jenkins instance>/sonarqube-webhook/ ********
	def sonarqubeScannerHome = tool 'SonarQubeScanner_V3'
	dir(applicationDir) {
		withSonarQubeEnv('SonarQube_V7') {
			sh 'ls -la'
			sh "${sonarqubeScannerHome}/bin/sonar-scanner" +
					" -Dsonar.projectKey=demandplannerui" +
					" -Dsonar.projectName=demandplannerui" +
					" -Dsonar.sources=src" +
					" -Dsonar.exclusions=**/node_modules/**,**/*.spec.ts" +
					" -Dsonar.tests=src" +
					" -Dsonar.test.inclusions=**/*.spec.ts" +
					" -Dsonar.ts.tslintconfigpath=tslint.json" +
					" -Dsonar.ts.lcov.reportpath=test-results/coverage/coverage.lcov" +
					" -Dsonar.sourceEncoding=UTF-8" + 
					" -Dsonar.projectVersion=${releaseVersion}"  
		}
	}
}

def uiDockerBuild(applicationDir, artifactName, releasedVersion) {
	dir(applicationDir) {
		echo "Starting Docker Image Creation..."
		sh "docker build -t ${artifactName}:${releasedVersion} ."
		echo "Docker Image Creation Complted..."
		sh "docker images"
	}
}

def promoteUIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP, dockerRegistryIP, serviceAccount) {
		sh """
				ssh -i  ~/.ssh/id_rsa ${serviceAccount}@${serverIP} 'docker run -e \'APP_ENV=${PROP_ENV}\' -v /local/mnt/dpui:/var/log/nginx -d -p 8098:80 --name ${artifactName} ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}'
			"""
}

/* ################################  COMMON (UI and API) Utility Methods ############################### */

def sendEmailNotification(subjectText, bodyText) {
	//subjectText = "JENKINS Notification : Successful Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
	//bodyText = """ <p>Successful: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p><p>Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"</p>"""
	def mailRecipients = 'r.satti@accenture.com, suresh.kumar.sahoo@accenture.com'
	emailext(
				subject: subjectText,
				body: bodyText,
				recipientProviders: [culprits(), developers(), requestor(), brokenTestsSuspects(), brokenBuildSuspects(), upstreamDevelopers()],
				to: "${mailRecipients}",
				replyTo: "${mailRecipients}"
			)

}