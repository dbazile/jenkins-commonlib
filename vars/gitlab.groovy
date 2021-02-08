/*
 * A GitLab notifier module.
 *
 * GitLab plugin is kind of finicky as of v1.5.13. Sending arbitrary status can
 * only be done via `updateGitlabCommitStatus()` which doesn't let you specify
 * which project/commit to send it to, instead it relies on a clunky "sniffing"
 * out the commit which often results in status being sent to the wrong project
 * under the wrong commit (usually happens in between when Jenkins pulls the
 * pipeline library and when your pipeline actually checks out project code).
 *
 * Dependencies:
 *   - curl
 *   - secret-text credential holding GitLab API access token
 */


import groovy.json.JsonOutput
import java.net.URLEncoder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException


def BASE_URL() { "https://gitlab.com" }


/**
 * @param args.commit        can be a commit-SHA/branch/tag
 * @param args.credentialId  credential to use for GitLab's API (default: gitlab-api)
 * @param args.project       name of the GitLab project (default: autodetected)
 * @param args.status        status to send
 * @param callback           block to execute (can't be used with 'status')
 */
def sendStatus(Map args=[:], Closure callback=null) {
    args = [
        commit: env.gitlabAfter    // present on builds triggered by GL plugin's webhook
                ?: params.GIT_REF  // job parameter that provides commit/branch/tag
                ?: 'HEAD',         // whatever is currently checked out
        credentialId: 'gitlab-api',
        project:      _resolveProject(),
        status:       '',
    ] << args

    //
    // Validate & normalize args
    //

    def credentialId = _readString(args, 'credentialId', '^.+$')
    def commit       = _gitRef(_readString(args, 'commit', '^.+$'))
    def project      = _readString(args, 'project', '^.+$')
    def status       = _readString(args, 'status')

    // It's not documented but GitLab API _can_ resolve branch names and
    // tags as long as they don't contain dots (why?)... Because of this,
    // we resolve the commit SHA explicitly.
    def commitSha
    try {
        commitSha = _resolveCommitSha(commit)
    }
    catch (Exception e) {
        echo("[commonlib.gitlab] WARNING: could not resolve commit SHA from reference: $commit")
        return
    }

    if (status && callback) {
        error("[commonlib.gitlab] can't send arbitrary with block use")
    }
    if (!status && !callback) {
        error("[commonlib.gitlab] must pass either an arbitrary status or define a block")
    }

    //
    // Send immediate status
    //

    if (status) {
        echo("[commonlib.gitlab] send '$status': ${BASE_URL()}/$project/-/commit/$commitSha" + (commit == commitSha ? '' : " ($commit)"))
        _sendStatus(credentialId, project, commitSha, status)
        return
    }

    //
    // Execute
    //

    echo("[commonlib.gitlab] monitor: ${BASE_URL()}/$project/-/commit/$commitSha" + (commit == commitSha ? '' : " ($commit)"))
    _sendStatus(credentialId, project, commitSha, 'running')
    try {
        callback()
        _sendStatus(credentialId, project, commitSha, 'success')
    }
    catch (Exception e) {
        _sendStatus(credentialId, project, commitSha, e in FlowInterruptedException ? 'canceled' : 'failed')
        throw e
    }
}


// Overload for the above to support: gitlab.sendStatus { ... }
def sendStatus(Closure callback) {
    return sendStatus([:], callback)
}


//
// Helpers
//


private String _gitRef(String raw) {
    return raw.trim().replaceAll('(?i)[^a-z0-9/_\\-\\.~]', '')
}


private String _readString(Map args, String key, String pattern = '.*') {
    def value = args.get(key, '')

    if (!(value in String || value in GString)) {
        error("[commonlib.gitlab] Invalid argument: '${key}' must be a string (value=$value)")
    }

    value = value.trim()

    if (!value.matches(pattern)) {
        error("[commonlib.gitlab] Invalid argument: '${key}' is malformed (pattern=${pattern}, value=${value})")
    }

    return value
}


private String _resolveCommitSha(String raw) {
    return sh(script: "#!/bin/bash -e\ngit rev-parse '${_gitRef(raw)}'", returnStdout: true).trim()
}


private String _resolveProject() {
    // https://javadoc.jenkins.io/plugin/git/hudson/plugins/git/GitSCM.html
    // https://javadoc.jenkins.io/plugin/git/hudson/plugins/git/UserRemoteConfig.html
    def url = scm.userRemoteConfigs.url[0]
    if (!url) {
        error('[commonlib.gitlab] could not resolve project; no repos configured')
    }

    def project

    // Short form, e.g., 'git@host:repo'
    project = url.replaceAll('^[^@]+@[^:]+:(?<project>.+?)(\\.git)?/?$', '${project}')
    if (project != url) {
        return project
    }

    // Long form, e.g., 'scheme://host:port/repo'
    project = url.replaceAll('^[^:]+://[^/]+(:\\d+)?/(?<project>.+?)(\\.git)?/?$', '${project}')
    if (project != url) {
        return project
    }

    error("[commonlib.gitlab] could not resolve project name from url: $url")
}


private Map _sendStatus(String credentialId, String project, String commit, String status) {
    // Prepare parameters
    commit = URLEncoder.encode(commit)
    project = URLEncoder.encode(project)
    status  = status.replaceAll('\\W+', '').toLowerCase()

    // Send request
    def url = BASE_URL() + "/api/v4/projects/${project}/statuses/${commit}?state=${status}"
    def responseText
    withCredentials([string(credentialsId: credentialId, variable: 'token')]) {
        responseText = sh(script: "#!/bin/bash -e\ncurl -sSL -X POST -H 'PRIVATE-TOKEN: " + token + "' '$url'", returnStdout: true)
    }

    // Parse response
    try {
        def res = readJSON(text: responseText)
        if (res.status != status) {
            echo("[commonlib.gitlab] WARNING: ${responseText}")
        }
    }
    catch(Exception e) {
        echo("[commonlib.gitlab] API returned unparseable JSON; raw value:\n---\n$responseText\n---")
    }
}
