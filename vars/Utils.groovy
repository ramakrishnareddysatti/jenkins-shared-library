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

def build(applicationDir, artifactName, releasedVersion) {
	dir(applicationDir) {
		echo "Starting Docker Image Creation..."
		sh "docker build -t ${artifactName}:${releasedVersion} ."
		echo "Docker Image Creation Complted..."
	}
}

//Save one or more images to a tar archive.
def pushImageToRepo(applicationDir, distroDirPath, artifactName, releasedVersion) {
	sshagent (credentials: ['git-repo-ssh-access']) {
		sh "docker images"
		dir (applicationDir) {
			//docker save -o <path for generated tar file> <existing image name>
			if (applicationDir == 'demandplannerapi') {
				sh "docker save -o target/${artifactName}-${releasedVersion}.tar ${artifactName}:${releasedVersion}"
				echo "Copying demandplannerapi tar file..."
				sh "cp -rf target/${artifactName}-${releasedVersion}.tar ${distroDirPath}"
			} else if (applicationDir == 'demandplannerui') {
				sh "docker save -o ${artifactName}-${releasedVersion}.tar ${artifactName}:${releasedVersion}"
				echo "Copying demandplannerui tar file..."
				sh "cp -rf ${artifactName}-${releasedVersion}.tar ${distroDirPath}"
			}
		}

		dir (distroDirPath) {
			sh "git pull origin master"
			sh "git add ${artifactName}-${releasedVersion}.tar"
			sh 'git commit -m "Jenkins Job:${JOB_NAME} pushing image tar file" '
			sh "git push origin HEAD:master"
		}
	}
}

def tagBranch(applicationDir, repoUrl, releasedVersion) {
	sshagent (credentials: ['git-repo-ssh-access']) {
		dir (applicationDir) {
			sh "ls -l"
			sh "git remote set-url origin ${repoUrl}"
			//sh "git tag ${IMAGE_BRANCH_PREFIX}-${BUILD_NUMBER}"
			sh "git tag ${releasedVersion}-${BUILD_NUMBER}"
			sh "git push --tags"
		}
	}
}

def saveImage(distroDirPath, artifactName, releasedVersion, destinationIP) {
	/*
	 timeout(activity: true, time: 20, unit: 'SECONDS') {
	 input message: 'Save to QA Env?', ok: 'Save'
	 }
	 */
	sh "scp -Cp ${distroDirPath}/${artifactName}-${releasedVersion}.tar centos@${destinationIP}:/home/centos"
	// TODO: should move to load image method
	sh "ssh -t centos@${destinationIP} 'ls && sudo docker load -i ${artifactName}-${releasedVersion}.tar' "
}

def deployAPIToDev(artifactName, releasedVersion, PROP_ENV) {
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
	//docker run -it -p 8080:80 angular-sample-app
	//sh "docker run -e 'SPRING_PROFILES_ACTIVE=${PROP_ENV}' -d -p 8099:8090 --name  ${artifactName} -t ${artifactName}"
	sh "docker run -d -p 8098:80 --name  ${artifactName} -t ${artifactName}:${releasedVersion}"
}

def removeImages(artifactName) {
	sh 'docker images -qf dangling=true | xargs --no-run-if-empty docker rmi'
	//sh 'docker image prune'

	 sh 'docker rmi $(docker images --filter=reference="demandplannerui" -q)'
	 sh 'docker images | grep "${artifactName}" | awk '{print $3}' | xargs -L1 docker rmi'	 
	 //sh "docker rmi $(docker images --filter=reference=${artifactName} -q)"
	 	//sh "docker rmi -f $(docker images | grep ${artifactName} | awk '{ print \\$3 }' )"
		 //sh "docker rmi -f $(docker images | grep ${artifactName})"

		 //docker rmi --force $(docker images | awk '/^<none>/ { print $3 }')

		 //sh "ls -l /tmp/environment-creation-enhanced/ocp-groups/ | grep -v total | grep -v yaml | awk '{print \$9}' > ${groupListDir}/fileList"
/*
	try {
		sh 'docker rmi -f $(docker images | grep ${artifactName} | awk \"{print $3}\")'
		//sh "docker rmi -f $(docker images | grep ${artifactName} | awk '{print \$3}')"
	} catch (err) {
		echo "Trying remove ${artifactName}: ${err}"
	}
	*/
}

//This stage installs all of the node dependencies, performs linting and builds the code.
def npmBuild(applicationDir, branchName, repoUrl) {
	//ng build generated 'dist' folder. To avoid putting 'dist' folder in  'demandplannerui'
	dir(applicationDir) {
		git branch: "${branchName}",
		credentialsId: 'git-repo-ssh-access',
		url: "${repoUrl}"

		sh '''
				node --version
				npm --version
				npm install -g npm@5.6.0 @angular/cli@~1.7.3
				npm install
				ng build --prod --aot
			'''
	}
}

def uiCodeQualityAnalysis(applicationDir) {
	// Configure a webhook in your SonarQube server pointing to <your Jenkins instance>/sonarqube-webhook/
	def sonarqubeScannerHome = tool 'SonarQubeScanner_V3'
	dir(applicationDir) {
		withSonarQubeEnv('SonarQube_V7') {
			sh 'ls -l'
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

def processQualityGate(){
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

def getArtifact(dirName) {
	def matcher = readFile("${dirName}/pom.xml") =~ '<artifactId>(.+?)</artifactId>'
	matcher ? matcher[0][1] : null
}

def getReleasedVersion(dirName) {
	def matcher = readFile("${dirName}/pom.xml") =~ '<version>(.+?)</version>'
	matcher ? matcher[0][1] : null
}

def sendNotification(buildStatus) {
	//def mailRecipients = 'r.satti@accenture.com, sashi.kumar.sharma@accenture.com, shresthi.garg@accenture.com, suresh.kumar.sahoo@accenture.com, s.b.jha@accenture.com';
	def mailRecipients = 'r.satti@accenture.com'

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
