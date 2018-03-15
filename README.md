# jenkins-commonlib-skeleton

This is a skeleton commonlib Jenkins pipeline library providing some
bargain-basement utility functions I often need in the projects I work on.



## Plugin Dependencies

- [Credentials](https://plugins.jenkins.io/credentials) (Official)
- [Pipeline Utility Steps](https://plugins.jenkins.io/pipeline-utility-steps) (Official)
- [Ansi Color](https://plugins.jenkins.io/ansicolor) (Official)



## Install

- Download as a `.zip`, create a new git repo and push it somewhere your Jenkins
  instance can retrieve.
- Navigate to or create the [folder](https://plugins.jenkins.io/cloudbees-folder)
  your project's pipelines live in and go to _Configure_
- On the _Configure_ page, add a _Shared Library_ pointing to the repo you
  created in the first step.



## Usage



### `constants`

##### Jenkinsfile

```groovy
library 'commonlib'

pipeline {
    agent any

    parameters {
        choice(name: 'config', choices: 'alpha\nbravo')
        choice(name: 'environment', choices: 'dev\nstage\nprod')
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    constants.init(this)
                }
            }
        }

        stage('Test') {
            steps {
                echo "${constants.LOREM_URL}"
            }
        }
    }
}
```

##### Console Output

```
[commonlib.constants.init] - Initializing (domain=bravo, environment=itworksyay)
[Pipeline] echo
[commonlib.constants.init] - Listing all constants:
---
constants.LOREM_URL = https://lorem.itworksyay.bravo.com
constants.IPSUM_PROFILE = delit-adipiscing
---

[Pipeline] echo
https://lorem.itworksyay.bravo.com
```

##### Shortcomings

You have to call `constants.init(this)` before attempting to read properties from
`constants`.  If not, you'll know pretty quickly as the pipeline will error-out.

If there's a better way to initialize sandbox-compliant "global/static"-y things
in Jenkins pipeline libraries, definitely file an issue and I'll make the change!



### `cloudfoundry`


This module provides bargain-basement blue-green deployments.  It splits the
deploy process into two stages: `deploy` and `release`.  Deploy will push a new
app that can theoretically run side-by-side with the current release so you can
run integration tests against the canary build before replacing the current
version (i.e., releasing).


##### Jenkinsfile

```groovy
library 'commonlib'


def _canaryRoute, _releaseRoute


pipeline {
    agent any

    stages {
        stage('Deploy') {
            steps {
                script {

                    _canaryRoute = cloudfoundry.deploy(
                        api:           'https://api.sys.example.com',
                        credentialsId: 'cloudfoundry',
                        domain:        'dev.example.com',
                        space:         'dev',
                        organization:  'myapp',
                        name:          'myapp',
                        manifest:      'manifest.yml',
                        props: [
                            'FOO': 'test-foo',
                            'BAR': 'test-bar',
                        ],
                    )

                    echo "CANARY URL: 'https://$_canaryRoute'"

                    input "Pausing so canary application at above URL can be inspected."

                    _releaseRoute = cloudfoundry.release(
                        api:           'https://api.sys.example.com',
                        credentialsId: 'cloudfoundry',
                        domain:        'dev.example.com',
                        space:         'dev',
                        organization:  'myapp',
                        name:          'myapp',
                        manifest:      'manifest.yml',
                    )

                    echo "RELEASE URL: 'https://$_releaseRoute'"
                }
            }
        }
    }
}
```

##### Console Output

```
[commonlib.cloudfoundry.deploy] Attempting to deploy 'myapp' using manifest 'manifest.yml':

---
applications:
- name: myapp
  buildpack: java_buildpack
  path: target/foo.war
  env:
    DB_CONNECTION_STRING: postgresql://test-foo:5432/test-bar


[commonlib.cloudfoundry.inSession] Logging into CloudFoundry (credentialsId=cloudfoundry, api=https://api.dev.example.com)
.
.
.
[commonlib.cloudfoundry.deploy] Deployed 'myapp' to 'myapp-b4edfb6'

Pausing so canary application at above URL can be inspected.
[Proceed] | Abort

[commonlib.cloudfoundry.release] Attempting to release '$candidateName'
[commonlib.cloudfoundry.inSession] Logging into CloudFoundry (credentialsId=cloudfoundry, api=https://api.dev.example.com)
[commonlib.cloudfoundry.release] Released 'myapp-b4edfb6' to 'myapp.example.com'
```

##### Shortcomings

- __Not__ tested with multi-app manifests, but I'm pretty sure they'll either
  break or confuse the deploy/release.
