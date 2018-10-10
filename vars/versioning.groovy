def release(Map args = [:]) {
    def NEXT_PATCH = 'patch'
    def NEXT_MAJOR = 'major'
    def NEXT_MINOR = 'minor'

    def DEFAULTS = [
        branch:    'master',
        next:      NEXT_PATCH,
        dryrun:    false,
        increment: true,
    ]

    args = DEFAULTS << args

    echo('[versioning.release] copying script to workspace')
    writeFile(file: 'versioning.py', text: libraryResource('versioning.py'))

    echo('[versioning.release] running script')
    sh 'python versioning.py'
}
