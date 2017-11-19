import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

import static java.time.ZonedDateTime.now

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def params= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()

    pipeline {
        agent none
        options {
            timeout(time: 5, unit: 'DAYS')
            disableConcurrentBuilds()
            ansiColor('xterm')
            timestamps()
        }
        stages {
            stage('Check build') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) } }
                agent any
                steps {
                    sh "test-script"
                    transitionIssue env.ISSUE_STATUS_OPEN, env.ISSUE_TRANSITION_START
                    ensureIssueStatusIs env.ISSUE_STATUS_IN_PROGRESS
                    script {
                        currentBuild.description = "Building from commit " + readCommitId()
                        env.MAVEN_OPTS = readProperties(file: 'Jenkinsfile.properties').MAVEN_OPTS
                        if (readCommitMessage() == "ready!") {
                            env.verification = 'true'
                        }
                    }
                    sh "mvn clean verify -B"
                }
            }
            stage('Wait for verification to start') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                steps {
                    transitionIssue env.ISSUE_TRANSITION_READY_FOR_CODE_REVIEW
                    waitUntilIssueStatusIs env.ISSUE_STATUS_CODE_REVIEW
                }
            }
            stage('Wait for verification slot') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent any
                steps {
                    failIfJobIsAborted()
                    sshagent([params.gitSshKey]) {
                        retry(count: 1000000) {
                            sleep 10
                            sh 'pipeline/git/available-verification-slot'
                        }
                    }
                }
                post {
                    failure { transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK }
                    aborted { transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK }
                }
            }
            stage('Prepare verification') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                environment {
                    crucible = credentials('crucible')
                }
                agent any
                steps {
                    script {
                        env.version = DateTimeFormatter.ofPattern('yyyy-MM-dd-HHmm').format(now(ZoneId.of('UTC'))) + "-" + readCommitId()
                        commitMessage = "${env.version}|" + issueId() + ": " + issueSummary()
                        sshagent([params.gitSshKey]) {
                            verifyRevision = sh returnStdout: true, script: "pipeline/git/create-verification-revision \"${commitMessage}\""
                        }
                        sh "pipeline/create-review ${verifyRevision} ${env.crucible_USR} ${env.crucible_PSW}"
                    }
                }
                post {
                    failure {
                        deleteVerificationBranch(params.gitSshKey)
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        deleteVerificationBranch(params.gitSshKey)
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Build artifacts') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                environment {
                    nexus = credentials('nexus')
                }
                agent any
                steps {
                    script {
                        checkoutVerificationBranch()
                        currentBuild.description = "Building ${env.version} from commit " + readCommitId()
                        env.MAVEN_OPTS = readProperties(file: 'Jenkinsfile.properties').MAVEN_OPTS
                        sh "mvn versions:set -B -DnewVersion=${env.version}"
                        sh """
                       mvn deploy -B -s settings.xml \
                       -Ddocker.release.username=${env.nexus_USR} \
                       -Ddocker.release.password=${env.nexus_PSW} \
                       -DdeployAtEnd=true
                       """
                    }
                }
                post {
                    failure {
                        deleteVerificationBranch(params.gitSshKey)
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        deleteVerificationBranch(params.gitSshKey)
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Wait for code review to finish') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                steps {
                    waitUntilIssueStatusIsNot env.ISSUE_STATUS_CODE_REVIEW
                    script {
                        env.codeApproved = "false"
                        if (issueStatusIs(env.ISSUE_STATUS_CODE_APPROVED))
                            env.codeApproved = "true"
                    }
                }
            }
            stage('Integrate code') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent any
                steps {
                    failIfJobIsAborted()
                    script {
                        checkoutVerificationBranch()
                        sshagent([params.gitSshKey]) {
                            sh 'git push origin HEAD:master'
                        }
                    }
                }
                post {
                    always {
                        deleteVerificationBranch(params.gitSshKey)
                    }
                    success {
                        deleteWorkBranch(params.gitSshKey)
                    }
                    failure {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Wait for manual verification to start') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                steps {
                    waitUntilIssueStatusIs env.ISSUE_STATUS_MANUAL_VERIFICATION
                }
            }
            stage('Deploy for manual verification') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                environment {
                    nexus = credentials('nexus')
                }
                agent {
                    dockerfile {
                        dir 'docker'
                        args '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts -u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        currentBuild.description = "Deploying ${env.version} to verification environment"
                        DOCKER_HOST = sh(returnStdout: true, script: 'pipeline/docker/define-docker-host-for-ssh-tunnel')
                        sshagent([params.verificationHostSshKey]) {
                            sh "DOCKER_HOST=${DOCKER_HOST} pipeline/docker/create-ssh-tunnel-for-docker-host ${params.verificationHostUser}@${params.verificationHostName}"
                        }
                        sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${DOCKER_HOST} docker/run ${env.nexus_USR} ${env.nexus_PSW} ${params.verificationStackName} ${env.version}"
                    }
                }
                post {
                    always {
                        sh "pipeline/docker/cleanup-ssh-tunnel-for-docker-host"
                    }
                }
            }
            stage('Wait for manual verification to finish') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                steps {
                    waitUntilIssueStatusIsNot env.ISSUE_STATUS_MANUAL_VERIFICATION
                    failIfJobIsAborted()
                    ensureIssueStatusIs env.ISSUE_STATUS_MANUAL_VERIFICATION_OK
                }
            }
            stage('Deploy for production') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                environment {
                    nexus = credentials('nexus')
                }
                agent {
                    dockerfile {
                        dir 'docker'
                        args '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts -u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        currentBuild.description = "Deploying ${env.version} to production environment"
                        DOCKER_HOST = sh(returnStdout: true, script: 'pipeline/docker/define-docker-host-for-ssh-tunnel')
                        sshagent([params.productionHostSshKey]) {
                            sh "DOCKER_HOST=${DOCKER_HOST} pipeline/docker/create-ssh-tunnel-for-docker-host ${params.productionHostUser}@${params.productionHostName}"
                        }
                        sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${DOCKER_HOST} docker/run ${env.nexus_USR} ${env.nexus_PSW} ${params.productionStackName} ${env.version}"
                    }
                }
                post {
                    always {
                        sh "pipeline/docker/cleanup-ssh-tunnel-for-docker-host"
                    }
                }
            }
        }
        post {
            success {
                echo "Success"
                notifySuccess()
            }
            unstable {
                echo "Unstable"
                notifyUnstable()
            }
            failure {
                echo "Failure"
                notifyFailed()
            }
            aborted {
                echo "Aborted"
                notifyFailed()
            }
            always {
                echo "Build finished"
            }
        }
    }

}

def checkoutVerificationBranch() {
    sh "git checkout verify/\${BRANCH_NAME}"
    sh "git reset --hard origin/verify/\${BRANCH_NAME}"
}

def deleteVerificationBranch(String sshKey) {
    echo "Deleting verification branch"
    sshagent([sshKey]) { sh "git push origin --delete verify/\${BRANCH_NAME}" }
    echo "Verification branch deleted"
}

def deleteWorkBranch(String sshKey) {
    sshagent([sshKey]) { sh "git push origin --delete \${BRANCH_NAME}" }
}

def failIfJobIsAborted() {
    if (env.jobAborted == 'true')
        error('Job was aborted')
}

boolean issueStatusIs(def targetStatus) {
    return issueStatus() == targetStatus
}

def waitUntilIssueStatusIs(def targetStatus) {
    env.jobAborted = 'false'
    try {
        retry(count: 1000000) {
            if (!issueStatusIs(targetStatus)) {
                sleep 10
                error "Waiting until issue status is ${targetStatus}..."
            }
        }
    } catch (FlowInterruptedException e) {
        env.jobAborted = "true"
    }
}

def waitUntilIssueStatusIsNot(def targetStatus) {
    env.jobAborted = 'false'
    try {
        retry(count: 1000000) {
            if (issueStatusIs(targetStatus)) {
                sleep 10
                error "Waiting until issue status is not ${targetStatus}..."
            }
        }
    } catch (FlowInterruptedException e) {
        env.jobAborted = "true"
    }
}

def notifyFailed() {
    emailext (
            subject: "FAILED: '${env.JOB_NAME}'",
            body: """<p>FAILED: Bygg '${env.JOB_NAME} [${env.BUILD_NUMBER}]' feilet.</p>
            <p><b>Konsoll output:</b><br/>
            <a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a></p>""",
            recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']]
    )
}

def notifyUnstable() {
    emailext (
            subject: "UNSTABLE: '${env.JOB_NAME}'",
            body: """<p>UNSTABLE: Bygg '${env.JOB_NAME} [${env.BUILD_NUMBER}]' er ustabilt.</p>
            <p><b>Konsoll output:</b><br/>
            <a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a></p>""",
            recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']]
    )
}

def notifySuccess() {
    if (isPreviousBuildFailOrUnstable()) {
        emailext (
                subject: "SUCCESS: '${env.JOB_NAME}'",
                body: """<p>SUCCESS: Bygg '${env.JOB_NAME} [${env.BUILD_NUMBER}]' er oppe og snurrer igjen.</p>""",
                recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']]
        )
    }
}

boolean isPreviousBuildFailOrUnstable() {
    if(!hudson.model.Result.SUCCESS.equals(currentBuild.rawBuild.getPreviousBuild()?.getResult())) {
        return true
    }
    return false
}

def issueId() {
    return env.BRANCH_NAME.tokenize('/')[-1]
}

def transitionIssue(def transitionId) {
    jiraTransitionIssue idOrKey: issueId(), input: [transition: [id: transitionId]]
}

def transitionIssue(def sourceStatus, def transitionId) {
    if (issueStatusIs(sourceStatus))
        transitionIssue transitionId
}

def ensureIssueStatusIs(def issueStatus) {
    if (!issueStatusIs(issueStatus))
        error "Issue status is not ${issueStatus}"
}

String issueStatus() {
    return jiraGetIssue(idOrKey: issueId()).data.fields['status']['id']
}

String issueSummary() {
    return jiraGetIssue(idOrKey: issueId()).data.fields['summary']
}

def readCommitId() {
    return sh(returnStdout: true, script: 'git rev-parse HEAD').trim().take(7)
}

def readCommitMessage() {
    return sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
}
