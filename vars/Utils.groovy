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
	sshagent (credentials: ['git-repo-ssh-access']) {
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
		credentialsId: 'git-repo-ssh-access',
		url: "${repoUrl}"
	}
}

/*
 * Responsible to remove "dangling images" and application "snapshot" images (if exists).
 *
 */
def removeDanglingImages(artifactName, serverIP) {
	try{
		/*
		sh """
			ssh centos@${serverIP} 'sudo su && docker images --no-trunc -aqf dangling=true | xargs --no-run-if-empty docker rmi && 
			docker images | grep snapshot | tr -s " " | cut -d " " -f 3 | xargs --no-run-if-empty docker rmi' 
			"""
		*/
		sh """
			ssh centos@${serverIP} 'docker images --no-trunc -aqf dangling=true | xargs --no-run-if-empty docker rmi && 
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
def stopContainer(artifactName, serverIP) {
	try{
		/*
		sh """
			ssh centos@${serverIP} 'sudo su && docker ps --no-trunc -aqf \'name=${artifactName}\' | xargs -I {} docker stop {} &&
			docker ps --no-trunc -aqf \'name=${artifactName}\' | xargs -I {} docker rm {}' 
			"""
		*/

		sh """
			ssh centos@${serverIP} 'docker ps --no-trunc -aqf \'name=${artifactName}\' | xargs -I {} docker stop {} &&
			docker ps --no-trunc -aqf \'name=${artifactName}\' | xargs -I {} docker rm {}' 
			"""	
	} catch(error) {
		echo "${error}"
	}
}

/*Demand Planner API Configuration */
def promoteAPIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP, dockerRegistryIP) {
		sh """
				ssh centos@${serverIP} 'docker run -e \'SPRING_PROFILES_ACTIVE=${PROP_ENV}\' -v /var/logs:/var/logs -d -p 8099:8090 --name ${artifactName} -t ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}'
				"""
}

/*Promote Priority Configuration */
def promotePCAPIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP, dockerRegistryIP) {
		sh """
				ssh -v centos@${serverIP} 'docker run -e \'SPRING_PROFILES_ACTIVE=${PROP_ENV}\' -v /var/logs:/var/logs -d -p 8091:8090 --name ${artifactName} -t ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}'
				"""
}

/* ################################  UI Utility Methods ############################### */

//This stage installs all of the node dependencies, performs linting and builds the code.
def npmBuild(applicationDir, branchName, repoUrl) {
	dir(applicationDir) {
		git branch: "${branchName}",
		credentialsId: 'git-repo-ssh-access',
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

def uiCodeQualityAnalysis(applicationDir) {
	//********* Configure a webhook in your SonarQube server pointing to <your Jenkins instance>/sonarqube-webhook/ ********
	def sonarqubeScannerHome = tool 'SonarQubeScanner_V3'
	dir(applicationDir) {
		withSonarQubeEnv('SonarQube_V7') {
			sh 'ls -la'
			sh "${sonarqubeScannerHome}/bin/sonar-scanner" +
					" -Dsonar.projectKey=demandplannerui" +
					" -Dsonar.sources=src" +
					" -Dsonar.exclusions=**/node_modules/**,**/*.spec.ts" +
					" -Dsonar.tests=src" +
					" -Dsonar.test.inclusions=**/*.spec.ts" +
					" -Dsonar.ts.tslintconfigpath=tslint.json" +
					" -Dsonar.ts.lcov.reportpath=test-results/coverage/coverage.lcov" +
					" -Dsonar.sourceEncoding=UTF-8"
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

def promoteUIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP) {
		sh """
				ssh centos@${serverIP} 'docker run -e \'APP_ENV=${PROP_ENV}\' -d -p 8098:80 --name ${artifactName} -t ${artifactName}:${releasedVersion}'
			"""
}

def promoteUIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP, dockerRegistryIP) {
		sh """
				ssh centos@${serverIP} 'docker run -e \'APP_ENV=${PROP_ENV}\' -d -p 8098:80 --name ${artifactName} -t ${dockerRegistryIP}:5000/${artifactName}:${releasedVersion}'
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


/* ################################  Promotion Pipeline ############################### */
def distroCheckout(distroDirPath, distroRepoUrl) {
	deleteDir()

	// Check for directory
	if(!fileExists(distroDirPath))
	{
		echo "${distroDirPath} doesn't exist.Continue cloning ..."

		dir(distroDirPath){
			git branch: 'master',
			credentialsId: 'git-repo-ssh-access',
			url: "${distroRepoUrl}"
		}
	}
	else {
		echo "${distroDirPath} is already exist.Continue updating ..."
		sshagent (credentials: ['git-repo-ssh-access']) {
			dir(distroDirPath) { sh "git pull origin HEAD:master" }
		}
	}
}

/* ################################  UNUSED Methods. Please Verify before DELETE ############################### */

def deployAPIToDev(artifactName, releasedVersion, PROP_ENV) {
	echo "releasedVersion in deployAPIToDev: ${releasedVersion}"
	sh "docker ps"
	def  containerId = sh (
			script: "docker ps --no-trunc -aqf 'name=${artifactName}'",
			returnStdout: true
			).trim()
	echo "containerId: ${containerId}"

	if (containerId != "") {
		sh "docker stop ${containerId}"
		sh "docker rm -f ${containerId}"
	}
	sh "docker run -e 'SPRING_PROFILES_ACTIVE=${PROP_ENV}' -d -p 8099:8090 --name ${artifactName} -t ${artifactName}:${releasedVersion}"
}

def deployUIToDev(artifactName, releasedVersion, PROP_ENV) {
	sh "docker ps"
	def  containerId = sh (
			script: "docker ps --no-trunc -aqf 'name=${artifactName}'",
			returnStdout: true
			).trim()
	echo "containerId: ${containerId}"

	if (containerId != "") {
		sh "docker stop ${containerId}"
		sh "docker rm -f ${containerId}"
	}
	sh "docker run -e 'APP_ENV=${PROP_ENV}' -d -p 8098:80 --name  ${artifactName} -t ${artifactName}:${releasedVersion}"
}

def commonAppCheckout(applicationDir, commonRepoUrl, branchName) {
	deleteDir()
	echo "Checkout in progress..."
	dir(applicationDir) {
		git branch: "${branchName}",
		credentialsId: 'git-repo-ssh-access',
		url: "${commonRepoUrl}"
	}
}

def sourceCodeCheckout(applicationDir, branchName, repoUrl, distroDirPath, distroRepoUrl) {
	deleteDir()
	echo "Checkout in progress..."
	dir(applicationDir) {
		git branch: "${branchName}",
		credentialsId: 'git-repo-ssh-access',
		url: "${repoUrl}"
	}

	// Check for directory
	if(!fileExists(distroDirPath))
	{
		echo "${distroDirPath} doesn't exist.Continue cloning ..."

		dir(distroDirPath){
			git branch: 'master',
			credentialsId: 'git-repo-ssh-access',
			url: "${distroRepoUrl}"
		}
	}
	else {
		echo "${distroDirPath} is already exist.Continue updating ..."
		sshagent (credentials: ['git-repo-ssh-access']) {
			dir(distroDirPath) { sh "git pull origin HEAD:master" }
		}
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

def loadImage(distroDirPath, artifactName, releasedVersion, serverIP) {
	   // BE CAREFUL WHILE DOING THIS. IT'S GOING TO REMOVE ALL THE **PREVIOUS** TAR(UI AND API) FILES
		// To Remove snapshot TAR Files
		try {
			sh "ssh centos@${serverIP} 'ls && rm *${artifactName}*.tar ' "
		} catch(error) {
			echo "${error}"
		}
	
	//sh "scp -Cp ${distroDirPath}/${artifactName}-${releasedVersion}.tar centos@${serverIP}:/home/centos"
	//sh "ssh centos@${serverIP} 'ls && docker load -i ${artifactName}-${releasedVersion}.tar' "
	sh "scp -C ${distroDirPath}/${artifactName}.tar centos@${serverIP}:/home/centos"
	sh "ssh centos@${serverIP} 'ls && docker load -i ${artifactName}.tar' "
}

def loadImageInProd(distroDirPath, artifactName, releasedVersion, serverIP) {
	sh "scp -C ${distroDirPath}/${artifactName}-${releasedVersion}.tar centos@${serverIP}:/home/centos"
	sh "ssh centos@${serverIP} 'ls && docker load -i ${artifactName}-${releasedVersion}.tar' "
}

def promoteAPIToEnv(artifactName, releasedVersion, PROP_ENV, serverIP) {
		sh """
				ssh centos@${serverIP} 'docker run -e \'SPRING_PROFILES_ACTIVE=${PROP_ENV}\' -v /var/logs/demandplannerapi:/var/logs -d -p 8099:8090 --name ${artifactName} -t ${artifactName}:${releasedVersion}'
				"""
}

def saveImage(applicationDir, distroDirPath, artifactName, releasedVersion, GIT_IMAGE_PUSH) {
		if (GIT_IMAGE_PUSH.toBoolean()) {
			echo "Save Image to Tar Archive and pushing Tar to Git Repo"
			saveImageToFS(applicationDir, distroDirPath, artifactName, releasedVersion)
			saveImageToRepo(distroDirPath, artifactName, releasedVersion)
		} else {
			echo "Save Image to Tar Archive and Copy image to ${distroDirPath}"
			saveImageToFS(applicationDir, distroDirPath, artifactName, releasedVersion)
		}
}

def saveCommmonArtifact(applicationDir, distroDirPath, artifactName, releasedVersion, GIT_IMAGE_PUSH) {
	if (GIT_IMAGE_PUSH.toBoolean()) {
		echo "Save Common Jar to Git Repo"
		dir (applicationDir) {
			sh "cp -rf target/${artifactName}-${releasedVersion}.jar ${distroDirPath}"
		}
		//saveImageToRepo(distroDirPath, artifactName, releasedVersion)
		sshagent (credentials: ['git-repo-ssh-access']) {
			dir (distroDirPath) {
				sh """
					mv ${artifactName}-${releasedVersion}.jar ${artifactName}.jar
					git add ${artifactName}.jar
					git commit -m "Jenkins Job:${JOB_NAME} pushing jar file with released Version:${artifactName}-${releasedVersion}"
					git push origin HEAD:master
				"""
			}
		}
	}	
}


/*
 * Save one or more images to a tar archive and copy to distro path.
 */
def saveImageToFS(applicationDir, distroDirPath, artifactName, releasedVersion) {
	sshagent (credentials: ['git-repo-ssh-access']) {
		sh "docker images"

		// Remove snapshot images in Jenkins box
		dir (distroDirPath) {
			def files = findFiles glob: '**/*snapshot*.tar'
			boolean exists = files.length > 0
			if(exists) {
				sh 'ls && rm -rf *snapshot*.tar'
			} else {
				echo "NO snapshot IMAGES IN ${applicationDir}"	
			}
		}

		dir (applicationDir) {
			//docker save -o <path for generated tar file> <existing image name>
			if (applicationDir == 'demandplannerapi') {
				//sh "docker save -o target/${artifactName}-${releasedVersion}.tar ${artifactName}:${releasedVersion}"
				sh "docker save -o target/${artifactName}.tar ${artifactName}:${releasedVersion}"
				echo "Copying demandplannerapi tar file to ${distroDirPath}"
				//sh "cp -rf target/${artifactName}-${releasedVersion}.tar ${distroDirPath}"
				sh "cp -rf target/${artifactName}.tar ${distroDirPath}"
			} else if (applicationDir == 'demandplannerui') {
				//sh "docker save -o ${artifactName}-${releasedVersion}.tar ${artifactName}:${releasedVersion}"
				sh "docker save -o ${artifactName}.tar ${artifactName}:${releasedVersion}"
				echo "Copying demandplannerui tar file to ${distroDirPath}"
				//sh "cp -rf ${artifactName}-${releasedVersion}.tar ${distroDirPath}"
				sh "cp -rf ${artifactName}.tar ${distroDirPath}"
			}
		}
	}
}

/*
 * Save one or more images to a tar archive and push to repo.
 */
def saveImageToRepo(distroDirPath, artifactName, releasedVersion) {
	echo "artifactName: ${artifactName}"
	echo "releasedVersion: ${releasedVersion}"
	
	sshagent (credentials: ['git-repo-ssh-access']) {
		dir (distroDirPath) {
			sh """
				git add ${artifactName}.tar
				git commit -m "Jenkins Job:${JOB_NAME} pushing tar file with released Version:${artifactName}-${releasedVersion}"
				git push origin HEAD:master
			"""
		}
	}
}

def sendNotification(buildStatus) {
	//def mailRecipients = 'r.satti@accenture.com, sashi.kumar.sharma@accenture.com, shresthi.garg@accenture.com, suresh.kumar.sahoo@accenture.com, s.b.jha@accenture.com';
	def mailRecipients = 'r.satti@accenture.com, suresh.kumar.sahoo@accenture.com'

	/* PRINT ALL ENVIRONMENT VARIABLES
	 sh 'env > env.txt'
	 for (String i : readFile('env.txt').split("\r?\n")) {
	 println i
	 }
	 */

	// build status of null means success
	def buildStatusVar =  buildStatus ?: 'SUCCESS'
	echo "buildStatusVar: ${buildStatusVar}"

	if (buildStatusVar == 'SUCCESS')
	{
		// notify users when the build is back to normal
		emailext(
				subject: "JENKINS Notification : Successful Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
				//  Generates beautiful email format. Since I didn't write the contents of "groovy-html.template", I am afraid to use
				//body: '''${SCRIPT, template="groovy-html.template"}''',
				body: """ <p>Successful: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p><p>Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"</p>""",
				recipientProviders: [culprits(), developers(), requestor(), brokenTestsSuspects(), brokenBuildSuspects(), upstreamDevelopers()],
				to: "${mailRecipients}",
				replyTo: "${mailRecipients}"
				)
	}
	else if (buildStatusVar == 'FAILURE')
	{
		// notify users when the Pipeline fails
		emailext(
				subject: "JENKINS Notification : FAILED Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
				//  Generates beautiful email format. Since I didn't write the contents of "groovy-html.template", I am afraid to use
				//body: '''${SCRIPT, template="groovy-html.template"}''',
				body: """ <p>FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p><p>Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"</p>""",
				recipientProviders: [culprits(), developers(), requestor(), brokenTestsSuspects(), brokenBuildSuspects(), upstreamDevelopers()],
				to: "${mailRecipients}",
				replyTo: "${mailRecipients}"
				)
	}
}
