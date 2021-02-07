# jenkins-commonlib

> A skeleton Jenkins pipeline library providing bargain-basement utility functions
I've often needed for the projects I work on.

If any of these are useful to you, fork this repo and carry it off to customize
it for your own projects!



## Plugin Dependencies

- [Ansi Color](https://plugins.jenkins.io/ansicolor) (Official)
- [Credentials](https://plugins.jenkins.io/credentials) (Official)
- [Pipeline Utility Steps](https://plugins.jenkins.io/pipeline-utility-steps) (Official)



## Install

- Download as a `.zip`, create a new git repo and push it somewhere your Jenkins
  instance can retrieve.
- Navigate to or create the [folder](https://plugins.jenkins.io/cloudbees-folder)
  your project's pipelines live in and go to _Configure_
- On the _Configure_ page, add a _Shared Library_ pointing to the repo you
  created in the first step.



## Usage


### `commonPipeline`

Mostly unopinionated general-purpose build wrapper with some simple
defaults and override hooks.

#### Jenkinsfile

```groovy
library 'commonlib'

node {

    //
    // Just go with the defaults
    //

    commonPipeline {
        stage('Init') {
            deleteDir()
            checkout scm
        }

        stage('Test') {
            sh 'gradle test'
        }

        stage('Archive') {
            archive 'build/libs'
        }
    }

    //
    // Customize everything for... reasons
    //

    commonPipeline(
        junitResults: 'build/test-results/**/*.xml',
        timeout:      15,
        triggers: [
            cron('*/5 * * * *'),
        ],
        params: [
            file(name: 'KEYSTORE'),
            password(name: 'KEYSTORE_PASS', defaultValue: ''),
        ],
    ) {
        stage('Init') {
            deleteDir()
            checkout scm
        }

        stage('Test') {
            sh 'gradle test'
        }

        stage('Archive') {
            archive 'build/libs'
        }
    }

}
```

### `constants`

In [resources/constants.yaml](resources/constants.yaml) you can define one set
of constants for each `config`, e.g.,:

````yaml
alpha:
  LOREM_URL:     'https://lorem.${environment}.alpha.com'
  IPSUM_PROFILE: 'dolor-sit-amet'

bravo:
  LOREM_URL:     'https://lorem.${environment}.bravo.com'
  IPSUM_PROFILE: 'delit-adipiscing'
````

#### Jenkinsfile

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

#### Console Output

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

#### Shortcomings

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

#### Jenkinsfile

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

#### Console Output

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

#### Shortcomings

- __Not__ tested with multi-app manifests, but I'm pretty sure they'll either
  break or confuse the deploy/release.


### `gitlab`

This module provides a consistent way of working with GitLab commit build
statuses. Written because the current version of the GitLab plugin (v1.5.13)
is finicky when it comes to sending status to the correct repo when used with
pipeline libraries.

#### Jenkinsfile

```groovy
library 'commonlib'


node {

    checkout scm

    // Send arbitrary status immediately
    gitlab.sendStatus(commit: 'tags/v1.2.3', status: 'success')

    // -OR-

    // Send 'running' when entering a block and 'success/failed/canceled'
    // depending on how it exits
    gitlab.sendStatus(commit: 'HEAD') {

        // do things

    }

}
```

#### Console Output

```
[commonlib.gitlab] send 'success': https://gitlab.com/dbazile/test/-/commit/074057e08e70cd63a88164aef1b568d216b6aa0d (tags/v1.2.3)
[Pipeline] withCredentials
Masking supported pattern matches of $token
[Pipeline] {
[Pipeline] sh
[Pipeline] }
[Pipeline] // withCredentials
[Pipeline] readJSON
[Pipeline] sh
[Pipeline] echo
[commonlib.gitlab] monitor: https://gitlab.com/dbazile/test/-/commit/2dd62fc18cd551e639833c4d94cfecaec40dd700 (HEAD)
[Pipeline] withCredentials
Masking supported pattern matches of $token
[Pipeline] {
[Pipeline] sh
[Pipeline] }
[Pipeline] // withCredentials
[Pipeline] readJSON
```

#### Shortcomings

- The commit sniffing is better than the GitLab plugin in that it doesn't get
  confused if called from a pipeline but I went with relative simplicity over
  making it totally bulletproof.


### `versioning`

Utility for automating a snapshot->RC->release dev cycle using Git tags and
semantic versioning.

This utility will update the version in the project definition file (i.e.,
build.gradle) and add/update a release information block to the README of the
project.

The actual versioning logic is handled by a dependency-free Python 2.7 script
(see [`/resources/versioning.py`](/resources/versioning.py)) (because I know
some people don't like Groovy and/or want to be able to run this thing on the
command line).  As such, the module is really just a wrapper around that
script.

By default, this will operate on a single branch.  It can be made to support
GitFlow by using `branchDev` and `branchRelease` accordingly.

This also targets Gradle because that's what I'm using right now but Maven
should be simple enough to integrate the artifact info lookup calls.

#### Jenkinsfile

```groovy
library 'commonlib'

node {
    stage('Release') {
        versioning.release(
            branchDev:     'master',
            branchRelease: 'master',
            gradleFile:    'build.gradle',
            readmeFile:    'README.md',
            nextVersion:   '',
            isRC:          false,
        )
    }
}
```

#### Console Output

```
[Pipeline] { (Release)
[Pipeline] echo
[commonlib.versioning] loading script
[Pipeline] libraryResource
[Pipeline] writeFile
[Pipeline] sh
[workspace] Running shell script
+ mktemp -d
+ TEMP_DIR=/tmp/tmp.TzwqF4XL3i
+ mv versioning.py /tmp/tmp.TzwqF4XL3i
+ python /tmp/tmp.TzwqF4XL3i/versioning.py --branch-dev=master --branch-release=master --gradle-file=build.gradle --readme-file=README.md
[versioning] PRERELEASE -- STARTED
[versioning] Checking Git state
[versioning] ==> checking out 'master'
[versioning] Collecting release info
[versioning] ==> current_version: 1.0.0-SNAPSHOT
[versioning] ==> release_version: 1.0.0
[versioning] ==>    next_version: 1.1.0-SNAPSHOT
[versioning] Updating release info block in 'README.md'
[versioning] ==> applying changes (group=dbazile, artifact=myproject, version=1.0.0)
[versioning] ==> attempting to replace block
[versioning] ==> block not found; appending to file
[versioning] ==> staging file
[versioning] Updating to release version in 'build.gradle'
[versioning] ==> applying new version '1.0.0'
[versioning] ==> verifying change
[versioning] ==> staging file
[versioning] Committing to 'master'
[versioning]     [master 9573842] [pre-release] 1.0.0
[versioning]      2 files changed, 12 insertions(+), 1 deletion(-)
[versioning] ==> tagging release as 'releases/v1.0.0'
[versioning] RELEASE -- STARTED
[versioning] POSTRELEASE -- STARTED
[versioning] Updating to next dev version in 'build.gradle'
[versioning] ==> checking out 'master'
[versioning] ==> applying new version '1.1.0-SNAPSHOT'
[versioning] ==> verifying change
[versioning] ==> staging file
[versioning] Committing to 'master'
[versioning]     [master 058d957] [post-release] 1.0.0
[versioning]      1 file changed, 1 insertion(+), 1 deletion(-)
[versioning] COMPLETE
[versioning] all thats left to do is to push
```


#### Shortcomings

- __Not__ tested with multi-project Gradle build hierarchies, but I'm pretty
  sure it'd just explode and/or break things.  Probably don't use it for that
  or update the code to handle that case.
