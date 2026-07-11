pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        IMAGE_NAME = "cv-domain-service"
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
                    junit 'target/surefire-reports/*.xml'
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
                branch 'main'
            }
            steps {
                // Placeholder until cv-infra exposes a deploy target:
                // push the image to ECR and roll the EC2 service via SSM.
                echo 'Deploy stage not yet implemented'
            }
        }
    }
}
