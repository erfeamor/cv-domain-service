pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        IMAGE_NAME = "cv-domain-service"
    }

    options {
        // Builds on the CI host take 44-97 s warm and up to 488 s with a cold
        // ~/.m2 (Jenkins history, 2026-08 to 09). 20 min is ~2.5x the worst cold
        // build. Nothing else bounds a wedged Maven download or base-image pull:
        // the host has one executor, shared with cv-database, and the CI-host
        // reaper only stops the host once busyExecutors == 0, so a hang would
        // block both repos and keep the host (and its bill) running.
        timeout(time: 20, unit: 'MINUTES')
    }

    stages {
        stage('Lint') {
            steps {
                sh 'mvn -B checkstyle:check'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn -B test'
            }
            post {
                always {
                    // allowEmptyResults: a timeout that aborts before Surefire
                    // writes any report must stay ABORTED, not turn into a
                    // "No test report files were found" error in this post step.
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B package -DskipTests'
            }
        }

        stage('Docker image') {
            steps {
                sh 'docker build -t $IMAGE_NAME:$GIT_COMMIT .'
            }
        }

        stage('Deploy') {
            when {
                branch 'master'
            }
            steps {
                // Not implemented. The ECR push and the instance roll are
                // T-112 on the cv-project board.
                echo 'Deploy stage not yet implemented'
            }
        }
    }
}
