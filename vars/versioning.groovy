/**
 * Utility for automating a snapshot->RC->release dev cycle using Git
 * tags and semantic versioning.
 *
 * <p>This utility will update the version in the project definition
 * file (i.e., build.gradle) and add/update a release information block
 * to the README of the project.
 *
 * <p>The actual versioning logic is handled by a dependency-free
 * Python 2.7 script (see <code>/resources/versioning.py</code>
 * (because I know some people don't like Groovy and/or want to be able
 * to run this thing on the command line).  As such, the module is
 * really just a wrapper around that script.
 *
 * <p>By default, this will operate on a single branch.  It can be made
 * to support GitFlow by using <code>branchDev</code> and
 * <code>branchRelease</code> accordingly.
 *
 * <p>This also targets Gradle because that's what I'm using right now
 * but Maven should be simple enough to integrate the artifact info
 * lookup calls.
 *
 * @param branchDev      Mainly here to support folks tied to GitFlow
 * @param branchRelease  Mainly here to support folks tied to GitFlow
 * @param gradleFile     The path to the gradle file to be modified
 * @param readmeFile     Path to the readme file to be modified
 * @param nextVersion    Explicitly define the next dev version to be
 *                       used after the release
 * @param isRC           If true, enables release-candidate semantics
 */
def release(Map args = [:]) {
    def DEFAULTS = [
        branchDev:     'master',
        branchRelease: 'master',
        gradleFile:    'build.gradle',
        readmeFile:    'README.md',
        nextVersion:   '',
        isRC:          false,
    ]

    //
    // Validate & normalize args
    //

    args = DEFAULTS << args

    // Real talk...  This is literally the __absolute bare minimum__ to
    // guard against any kind of crazy old inputs.
    def PATTERN_ALLOWABLE = '^[A-Za-z0-9_+./:@ -]*$'

    def branchDev     = _readString(args, 'branchDev', PATTERN_ALLOWABLE)
    def branchRelease = _readString(args, 'branchRelease', PATTERN_ALLOWABLE)
    def gradleFile    = _readString(args, 'gradleFile', PATTERN_ALLOWABLE)
    def readmeFile    = _readString(args, 'readmeFile', PATTERN_ALLOWABLE)
    def nextVersion   = _readString(args, 'nextVersion', PATTERN_ALLOWABLE)
    def isRC          = _readBool(args, 'isRC')

    def extraArgs = ''
    if (isRC) {
        extraArgs += ' --rc'
    }
    if (nextVersion != '') {
        extraArgs += " --next-version='${nextVersion}'"
    }

    echo('[commonlib.versioning] loading script')
    writeFile(file: 'versioning.py', text: libraryResource('versioning.py'))
    sh """
        TEMP_DIR=\$(mktemp -d)

        mv versioning.py "\$TEMP_DIR"

        python "\$TEMP_DIR/versioning.py" \
            --branch-dev='${branchDev}' \
            --branch-release='${branchRelease}' \
            --gradle-file='${gradleFile}' \
            --readme-file='${readmeFile}' \
            ${extraArgs}
    """
}


private boolean _readBool(Map args, String key) {
    return args.get(key) == true
}


private String _readString(Map args, String key, String pattern) {
    def value = args.get(key, '')

    if (!(value in String || value in GString)) {
        error("[commonlib.versioning] Invalid argument: '${key}' must be a string (value=$value)")
    }

    value = value.trim()

    if (!value.matches(pattern)) {
        error("[commonlib.versioning] Invalid argument: '${key}' is malformed (pattern=${pattern}, value=${value})")
    }

    return value
}
