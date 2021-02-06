/*
 * Provides bargain-basement blue-green deployments into CloudFoundry.
 *
 * This module splits the deploy process into two stages: `deploy`
 * and `release`.  Deploy will push a new app that can theoretically run
 * side-by-side with the current release so you can run integration
 * tests against the canary build before replacing the current version
 * (i.e., releasing).
 *
 * Usage:
 *
 *     _canaryRoute = cloudfoundry.deploy(
 *         api:          'https://api.sys.example.com',
 *         credentialId: 'cloudfoundry',
 *         domain:       'dev.example.com',
 *         space:        'dev',
 *         organization: 'myapp',
 *         name:         'myapp',
 *         manifest:     'manifest.yml',
 *         props: [
 *             'FOO': 'test-foo',
 *             'BAR': 'test-bar',
 *         ],
 *     )
 *
 *     echo "CANARY URL: 'https://$_canaryRoute'"
 *
 *     input "Pausing so canary application at above URL can be inspected."
 *
 *     _releaseRoute = cloudfoundry.release(
 *         api:          'https://api.sys.example.com',
 *         credentialId: 'cloudfoundry',
 *         domain:       'dev.example.com',
 *         space:        'dev',
 *         organization: 'myapp',
 *         name:         'myapp',
 *         manifest:     'manifest.yml',
 *     )
 *
 *     echo "RELEASE URL: 'https://$_releaseRoute'"
 */


/**
 * @param args.api           CF API URL
 * @param args.credentialId  Credential ID to use with CF API
 * @param args.domain        CF domain to deploy to
 * @param args.manifest      Path to manifest file
 * @param args.name          Name of application
 * @param args.organization  Organization to deploy under
 * @param args.space         Space to deploy into
 * @param args.props         Extra properties to add to deployed app
 */
def deploy(Map args) {
    args = [
        api:          '',
        credentialId: '',
        domain:       '',
        manifest:     'manifest.yml',
        name:         '',
        organization: '',
        space:        '',
        props:        [:],
    ] << args

    //
    // Validate & normalize args
    //

    def api          = _readString(args, 'api')
    def credentialId = _readString(args, 'credentialId')
    def domain       = _readString(args, 'domain')
    def manifest     = _readString(args, 'manifest')
    def name         = _readString(args, 'name').replaceAll('[^A-Za-z0-9-]+', '-')
    def organization = _readString(args, 'organization')
    def space        = _readString(args, 'space')

    if (args.props) {
        _fillPlaceholders(args.manifest, args.props)
    }

    def version = _getVersion()

    def candidateName = "$name-$version"

    //
    // Perform deployment
    //

    echo("[commonlib.cloudfoundry.deploy] Attempting to deploy '${candidateName}' using manifest '${manifest}':\n\n${readFile(manifest)}\n\n")

    inSession(credentialId: credentialId, api: api, organization: organization, space: space) {
        try {
            sh """
                cf push ${candidateName} -f ${manifest} --hostname ${candidateName} --no-start
                cf set-env ${candidateName} DEPLOY_APPNAME ${name}
                cf set-env ${candidateName} DEPLOY_VERSION ${version}
                cf start ${candidateName}
            """
        }
        catch (Exception err) {
            error("[commonlib.cloudfoundry.deploy] Could not deploy '${candidateName}': ${err}")
        }
    }

    //
    // Report app route to caller if exists
    //

    def route = "$candidateName.$domain"
    if (_appIsUnrouted(name, manifest)) {
        route = null
    }

    echo("[commonlib.cloudfoundry.deploy] Deployed '${candidateName}' to '${route}'")

    return route
}


/**
 * @param args.api           CF API URL
 * @param args.credentialId  Credential ID to use with CF API
 * @param args.organization  Organization to deploy under
 * @param args.space         Space to deploy into
 */
def inSession(Map args, callback) {
    args = [
        api:          '',
        credentialId: '',
        organization: '',
        space:        '',
    ] << args

    // Parse arguments
    def api           = _readString(args, 'api')
    def credentialId  = _readString(args, 'credentialId')
    def organization  = _readString(args, 'organization')
    def space         = _readString(args, 'space')

    echo("[commonlib.cloudfoundry.inSession] Logging into CloudFoundry (credentialId=${credentialId}, api=${api})")

    env.CF_HOME = "${env.WORKSPACE}/.cf"

    ansiColor('xterm') {
        withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'PCF_USER', passwordVariable: 'PCF_PASS')]) {
            try {
                sh """
                    mkdir -p \$CF_HOME
                    cf api ${api}
                    cf auth "${PCF_USER}" "${PCF_PASS}"
                    cf target -o ${organization} -s ${space}
                """
            }
            catch(Exception err) {
                error("[commonlib.cloudfoundry.inSession] Could not log in to '${api}': ${err}")
            }

            callback()
        }
    }
}


/**
 * @param args.api           CF API URL
 * @param args.credentialId  Credential ID to use with CF API
 * @param args.domain        CF domain to deploy to
 * @param args.manifest      Path to manifest file
 * @param args.name          Name of application
 * @param args.organization  Organization to deploy under
 * @param args.space         Space to deploy into
 */
def release(Map args) {
    args = [
        api:          '',
        credentialId: '',
        domain:       '',
        manifest:     'manifest.yml',
        name:         '',
        organization: '',
        space:        '',
    ] << args

    //
    // Validate & normalize args
    //

    def api           = _readString(args, 'api')
    def credentialId  = _readString(args, 'credentialId')
    def domain        = _readString(args, 'domain')
    def manifest      = _readString(args, 'manifest')
    def name          = _readString(args, 'name').replaceAll('[^A-Za-z0-9-]+', '-')
    def organization  = _readString(args, 'organization')
    def space         = _readString(args, 'space')

    def version       = _getVersion()
    def unrouted      = _appIsUnrouted(name, manifest)

    def candidateName = "$name-$version"

    //
    // Perform release
    //

    echo("[commonlib.cloudfoundry.release] Attempting to release '$candidateName'")

    inSession(credentialId: credentialId, api: api, organization: organization, space: space) {
        try {
            if (!unrouted) {
                sh "cf map-route ${candidateName} ${domain} --hostname ${name}"
            }

            if (_appExists(name)) {
                sh "cf delete ${name} -f"
            }

            sh "cf rename ${candidateName} ${name}"
        }
        catch(Exception err) {
            error("[commonlib.cloudfoundry.release] Could not release '${candidateName}': ${err}")
        }
    }

    //
    // Report app route to caller if exists
    //

    def route = "$name.$domain"
    if (unrouted) {
        route = null
    }

    echo("[commonlib.cloudfoundry.release] Released '${candidateName}' to '${route}'")

    return route
}


//
// Helpers
//


private boolean _appExists(String name) {
    return sh(script: "cf app --guid ${name} >/dev/null 2>&1", returnStatus: true) == 0
}


private boolean _appIsUnrouted(String name, String manifest) {
    try {
        def app = readYaml(file: manifest).applications?.find({a -> a.name == name})
        if (!app) {
            error("[commonlib.cloudfoundry] Application '${name}' not found in manifest '${manifest}'")
        }
        return app.get('no-route', false)
    }
    catch(Exception err) {
        error("[commonlib.cloudfoundry] Could not open '${manifest}' for reading (err=${err})")
    }
}


private void _fillPlaceholders(String filepath, Map props) {
    try {
        def contents = readFile(filepath)

        props.each { key, value ->
            contents = contents.replaceAll("____${key}____", value)
        }

        writeFile(file: filepath, text: contents)
    }
    catch(FileNotFoundException err) {
        error("[commonlib.cloudfoundry] Could not open '${filepath}' for reading/writing (err=${err})")
    }
    catch (Exception err) {
        error("[commonlib.cloudfoundry] Could not fill property placeholders in '${filepath}' (err=${err})")
    }
}


private String _getVersion() {
    try {
        return sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
    }
    catch(Exception err) {
        error('[commonlib.cloudfoundry] Could not determine git commit SHA.')
    }
}


private String _readString(Map args, String key, boolean required = true) {
    def value = args.get(key, '')

    if (!(value in String || value in GString)) {
        error("[commonlib.cloudfoundry] Invalid argument: '${key}' must be a string (value=$value)")
    }

    value = value.trim()

    if (required && !value) {
        error("[commonlib.cloudfoundry] Invalid argument: '${key}' cannot be blank")
    }

    return value
}
