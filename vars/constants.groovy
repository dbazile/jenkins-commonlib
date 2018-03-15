def init(context) {
    def document, config

    //
    // Get configuration
    //

    try {
        document = readYaml(text: libraryResource('constants.yaml'))
    }
    catch(Exception e) {
        error("[commonlib.constants.init] Could not parse `constants.yaml`: ${e}")
    }

    config = document.get(context.params.config)
    if (config == null) {
        error("[commonlib.constants.init] Could not find config '${context.params.config}'")
    }

    //
    // Normalize values
    //

    config.each { key, value ->
        if (value in String) {
            config[key] = value.replaceAll('\\$\\{environment\\}', context.params.environment)
        }
    }

    //
    // Dump values
    //

    def lines = []
    config.each { k, v -> lines.add("constants.${k} = ${v}") }
    println "[commonlib.constants.init] Listing all constants:\n---\n${lines.join('\n')}\n---"

    //
    // Make available to pipelines as `constants.FOO`, `constants.BAR`
    //

    config.each { key, value ->
        context.constants."${key}" = value
    }
}
