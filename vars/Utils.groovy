

def getArtifact(dirName) {
	def matcher = readFile("${dirName}/pom.xml") =~ '<artifactId>(.+?)</artifactId>'
	matcher ? matcher[0][1] : null
}

def getReleasedVersion(dirName) {
	def matcher = readFile("${dirName}/pom.xml") =~ '<version>(.+?)</version>'
    matcher ? matcher[0][1] : null 	
}
def sendNotification(buildStatus) {
	//def mailRecipients = 'r.satti@accenture.com, sashi.kumar.sharma@accenture.com, shresthi.garg@accenture.com, suresh.kumar.sahoo@accenture.com';
	def mailRecipients = 'r.satti@accenture.com'
    echo "buildStatus: ${buildStatus}"

	// build status of null means success
    buildStatus =  buildStatus ?: 'SUCCESS'
	if (buildStatus == 'SUCCESS')
	{
		// notify users when the build is back to normal
		emailext(
				subject: "JENKINS Notification : Successful Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
				//body: '''${SCRIPT, template="groovy-html.template"}''',
                body: """ <p>Successful: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p><p>Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"</p>""",
				recipientProviders: [culprits(), developers(), requestor(), brokenTestsSuspects(), brokenBuildSuspects(), upstreamDevelopers()],
				to: "${mailRecipients}",
				replyTo: "${mailRecipients}"
				)
	}
	else if (buildStatus == 'FAILED')
	{
		// notify users when the Pipeline fails
		emailext(
				subject: "JENKINS Notification : FAILED Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
				//body: '''${SCRIPT, template="groovy-html.template"}''',
                body: """ <p>FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p><p>Check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"</p>""",
				recipientProviders: [culprits(), developers(), requestor(), brokenTestsSuspects(), brokenBuildSuspects(), upstreamDevelopers()],
				to: "${mailRecipients}",
				replyTo: "${mailRecipients}"
				)
	}
}

/* Generats beautiful email format. Since I didn't write the contents of "groovy-html.template", I am afraid to use
def sendNotification(String buildStatus) {
	//def mailRecipients = 'r.satti@accenture.com, sashi.kumar.sharma@accenture.com, shresthi.garg@accenture.com, suresh.kumar.sahoo@accenture.com';
	def mailRecipients = 'r.satti@accenture.com'
	
	// build status of null means success
    buildStatus =  buildStatus ?: 'SUCCESS'
	if (buildStatus == 'SUCCESS')
	{
		// notify users when the build is back to normal
		emailext(
				subject: "JENKINS Notification : Successful Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
				body: '''${SCRIPT, template="groovy-html.template"}''',
				recipientProviders: [culprits(), developers(), requestor(), brokenTestsSuspects(), brokenBuildSuspects(), upstreamDevelopers()],
				to: "${mailRecipients}",
				replyTo: "${mailRecipients}"
				)
	}
	else if (buildStatus == 'FAILED')
	{
		// notify users when the Pipeline fails
		emailext(
				subject: "JENKINS Notification : FAILED Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
				body: '''${SCRIPT, template="groovy-html.template"}''',
				recipientProviders: [culprits(), developers(), requestor(), brokenTestsSuspects(), brokenBuildSuspects(), upstreamDevelopers()],
				to: "${mailRecipients}",
				replyTo: "${mailRecipients}"
				)
	}
}*/
