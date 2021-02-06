/**
 * Mostly unopinionated general-purpose build wrapper with some simple
 * defaults and override hooks.
 *
 * Usage:
 *
 *     commonPipeline(timeout: 1234, triggers: [ ... ]) {
 *         stage('Foo') { ... }
 *     }
 */


import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException


/**
 * @param args.concurrency   If true, enables this job to build concurrently
 * @param args.junitResults  Glob pattern for junit test results
 * @param args.timeout       Maximum number of minutes this job should run
 * @param args.triggers      Argument to <code>pipelineTriggers()</code>
 * @param args.params        Argument to <code>parameters()</code>
 * @param buildStages        Closure that should contain actual build stages
 */
def call(Map args = [:], Closure buildStages) {
    args = [
        concurrency:  false,
        junitResults: 'build/test-results/**/*.xml',
        timeout:      10,

        params: [
            string(
                name:         'GIT_REF',
                description:  '',
                defaultValue: '',
                trim:         true,
            ),
            booleanParam(
                name:         'SKIP_SCANS',
                description:  '',
                defaultValue: false,
            ),
            booleanParam(
                name:         'SKIP_TESTS',
                description:  '',
                defaultValue: false,
            ),
        ],

        triggers: [
            pollSCM(''),
        ],
    ] << args

    //
    // Validate & normalize args
    //

    def concurrency  = _readBool(args, 'concurrency')
    def junitResults = _readString(args, 'junitResults')
    def timeout_     = _readInt(args, 'timeout')
    def params_      = _readList(args, 'params')
    def triggers     = _readList(args, 'triggers')

    //
    // Perform build
    //

    try {

        // gitlab.sendStatus(state: 'running')
        // notifyBitbucket()                           // Stash Notifier plugin

        _configureProperties(params_, triggers, concurrency)

         ansiColor('xterm') {
             timeout(time: timeout_, unit: 'MINUTES') {
                 buildStages()
             }
         }

        // notifyBitbucket()                           // Stash Notifier plugin
        // gitlab.sendStatus(state: 'success')

    }
    catch(Exception e) {

        // gitlab.sendStatus(state: 'failed')
        // notifyBitbucket()                           // Stash Notifier plugin

        throw e
    }
    catch(FlowInterruptedException e) {

        // gitlab.sendStatus(state: 'canceled')
        // notifyBitbucket()                           // Stash Notifier plugin

    }
    finally {
        _collectTestResults(junitResults)
    }
}


//
// Helpers
//


private void _collectTestResults(String pattern) {
    try {
        junit "${pattern}"
    }
    catch(Exception e) {
        echo("[commonlib.commonPipeline] Could not collect test results: $e (pattern=$pattern)")
    }
}


private void _configureProperties(List params_, List triggers, boolean concurrency) {
    try {
        def items = [
            pipelineTriggers(triggers),
            parameters(params_),
        ]

        if (concurrency) {
            items.add(disableConcurrentBuilds())
        }

        properties(items)
    }
    catch(Exception e) {
        echo("[commonlib.commonPipeline] Could not configure job properties: $e")
        throw e
    }
}


private boolean _readBool(Map args, String key) {
    return args.get(key) == true
}


private int _readInt(Map args, String key) {
    def value = args.get(key)

    if (!(value in Integer)) {
        error("[commonlib.commonPipeline] Invalid argument: '${key}' must be a number (value=$value)")
    }

    return value
}


private List _readList(Map args, String key) {
    def value = args.get(key)

    if (!(value in List)) {
        error("[commonlib.commonPipeline] Invalid argument: '${key}' must be a list (value=$value)")
    }

    return value
}


private String _readString(Map args, String key, String pattern = '.*') {
    def value = args.get(key, '')

    if (!(value in String || value in GString)) {
        error("[commonlib.commonPipeline] Invalid argument: '${key}' must be a string (value=$value)")
    }

    value = value.trim()

    if (!value.matches(pattern)) {
        error("[commonlib.commonPipeline] Invalid argument: '${key}' is malformed (pattern=${pattern}, value=${value})")
    }

    return value
}
