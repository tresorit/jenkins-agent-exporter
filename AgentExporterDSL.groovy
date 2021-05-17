import javaposse.jobdsl.dsl.DslFactory

freeStyleJob('AgentExporter') {
    description('Gathers Jenkins agent metrics and exposes to Prometheus scraper')

    triggers {
        cron('H/5 * * * *') // every five minute
    }

    logRotator {
        numToKeep(100)
    }

    label('master')

    concurrentBuild(false)

    steps {
        systemGroovyCommand(DslFactory.readFileFromWorkspace('AgentExporterDSL.groovy')) {
            sandbox(false) // not tested with sandbox mode
        }
    }

    wrappers {
        timeout {
            abortBuild()
            absolute(1)
            writeDescription('Script execution took more than 1 minute, aborted by timeout!')
        }
    }

    publishers {
        archiveArtifacts('prometheus')
    }
}
