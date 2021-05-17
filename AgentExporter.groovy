/*
# About
This script is executed periodically by a Jenkins job,
resulting 'prometheus' file is archived as artifact and
exposed for Prometheus scraper to gather metrics.

# Online/offline check
First it was a bit confusing, so here I explain what states an agent/Computer/Node can have.
 - is_connected: The Jenkins master and the agent.jar process on the computer has a communication channel.
 - is_online: The agent is connected and the node was not put offline on the agent configuration page.
 - has_offline_message: The agent was put offline by an administrator.

When an agent is not connected the is_online metric is always 0.
For alerting it is really useful info if it was intentionaly put offline by an admin or not.

# Multi agent nodes
In our setup we've got some bigger Nodes with more resources,
that use persistent folders dedicated to be used exclusively by a build.
To optimize this use-case we have more of such persistent folders
on bigger Nodes and thus dedicate separate Jenkins agents to them.
You may refine the script to avoid extra logic supporting that use-case.

# Tracing
To help later investigations for any issues in the script,
we call the trace function, which measures time spent between repeated operations.
This way did we found out that calling node.getClockDifference()
is actually measuring the metric in contrast to the
monitorData['hudson.node_monitors.ClockMonitor'].diff which is a cached value
which is better for us. If the script did not finished in the given timeframe,
you can even check at what exact part of the code got it stuck.
*/
import hudson.util.RemotingDiagnostics
import jenkins.model.Jenkins
import hudson.FilePath
import groovy.time.TimeCategory
import groovy.time.TimeDuration

ipMap = [:]
nameIpCache = [:]
lastStepTime = null
stepDurations = [:]
notConnectedAgents = []


void main() {
    startTime = System.currentTimeMillis()

    agentMetrics = initializeAgentMetrics()
    internalMetrics = initializeInternalMetrics()

    println('Gathering agent metrics')
    Jenkins.instance.nodes.each{ gatherMetrics(it, agentMetrics) }

    println('Gathering internal metrics')
    currentTime = System.currentTimeMillis()
    generateInternalMetrics(internalMetrics, startTime, currentTime)

    println('Exporting agent metrics')
    metricsString = generatePrometheusPage(agentMetrics + internalMetrics)

    println('Writing Prometheus scrape target file')
    writeMetrics(metricsString)

    println('Aggregated time betweeen given steps:')
    stepDurations.each { name, duration ->
        println(name + ': ' + duration.toMilliseconds() + ' ms')
    }

    //println(metricsString)
}


Map initializeAgentMetrics() {
    String prefix = 'jenkins_agent'

    Map metrics = [
        'clock_diff': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/ClockMonitor.html',
            'help': 'Agent system time difference in ms relative to Jenkins master\'s',
        ],
        'response_time': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/ResponseTimeMonitor.html',
            'help': 'Agent round-trip response time in ms from Jenkins master'
        ],
        'mem_total': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/SwapSpaceMonitor.MemoryUsage2.html',
            'help': 'Agent\'s total memory space in bytes'
        ],
        'mem_free': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/SwapSpaceMonitor.MemoryUsage2.html',
            'help': 'Agent\'s free memory space in bytes'
        ],
        'swap_total': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/SwapSpaceMonitor.MemoryUsage2.html',
            'help': 'Agent\'s total swap space in bytes '
        ],
        'swap_free': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/SwapSpaceMonitor.MemoryUsage2.html',
            'help': 'Agent\'s free swap space in bytes '
        ],
        'jenkins_dir_free': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/DiskSpaceMonitorDescriptor.DiskSpace.html',
            'help': 'Agent\'s free disk space in byte(?), on which disk the agent is running'
        ],
        'jenkins_dir_total': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/DiskSpaceMonitorDescriptor.DiskSpace.html',
            'help': 'Agent\'s total disk space in gigabyte, on which disk the agent is running'
        ],
        'tmp_dir_free': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/TemporarySpaceMonitor.html',
            'help': 'Agent\'s free space in byte(?), on the temporary filesystem'
        ],
        'tmp_dir_total': [
            'ref': 'https://javadoc.jenkins.io/hudson/node_monitors/TemporarySpaceMonitor.html',
            'help': 'Agent\'s total space in gigabyte, on the temporary filesystem'
        ],
        'is_online': [
            'ref': 'https://javadoc.jenkins.io/hudson/model/Computer.html#isOnline--',
            'help': 'Whether the agent can receive builds for execution'
        ],
        'has_offline_message': [
            'ref': 'https://javadoc.jenkins.io/hudson/model/Computer.html#getOfflineCause--',
            'help': 'Whether the agent has a manually set offline message (with "disconnected by username:" part, so can not be set, but empty)'
        ],
        'is_connected': [
            'help': 'Whether the agent is connected to the Jenkins master'
        ],
        'executors_busy': [
            'ref': 'https://javadoc.jenkins.io/hudson/model/Computer.html#countBusy--',
            'help': 'Agent\'s number of executors doing build at the time'
        ],
        'executors_total': [
            'ref': 'https://javadoc.jenkins.io/hudson/model/Computer.html',
            'help': 'Agent\'s total configured executors number'
        ]
    ]

    metrics.each { k, v ->
        v.collectedData = [] // initialize
        v.prefix = prefix
        v.type =  v.type == null ? 'gauge' : v.type // default type
    }

    return metrics
}


Map initializeInternalMetrics() {
    String prefix = 'jenkins_agent_scrape'

    Map metrics = [
        'duration': [
            'type': 'gauge',
            'help': 'Time in milliseconds during the agent metrics were collected'
        ],
        'last_collection_time': [
            'type': 'counter',
            'help': 'EPOCH time in milliseconds when the collection started'
        ],

    ]

    metrics.each { k, v ->
        v.collectedData = [] // initialize
        v.prefix = prefix
    }

    return metrics
}


def checkNode(node) {
    name = node.displayName
    println('Checking node: ' + name)

    String ip = getIP(node.toComputer())
    if (ip == null) {
        notConnectedAgents.add(name)
        println('\tIP adress could not be determined, will not gather metrics. Possibly agent is not connected')
        return ip
    }
    nameIpCache[name] = ip
    println('\tip: ' + ip)

    if (ipMap.containsKey(ip)) {
        println('\tAgent shares host machine with: ' + ipMap[ip][0].displayName)
        ipMap[ip].add(node)
        // It is better to gather data more times, as some metrics are different for each agent connection (online/busy executors)
    } else {
        ipMap[ip] = [node]
    }
    return ip
}


String getIP(computer) {
  channel = computer.channel
  if (channel != null) {
    ipMatch = channel.name =~ /\/([\d\.]+):/
    if(ipMatch){
        ip = ipMatch[0][1]
        if(!ip.startsWith('127.')){
            return ip
        }
    }
    ip = RemotingDiagnostics.executeGroovy('println InetAddress.localHost.hostAddress', computer.channel).trim()
    return ip
  }
  return null
}


void trace(String step) {
    println('Reached step: ' + step)
    if (lastStepTime == null) {
        lastStepTime = new Date()
        return
    }
    Date now = new Date()
    TimeDuration elapsed = TimeCategory.minus( now, lastStepTime )
    lastStepTime = now
    if (stepDurations.containsKey(step)) {
        stepDurations[step] += elapsed
    } else {
        stepDurations[step] = elapsed
    }
}


void generateInternalMetrics(Map metrics, long startTime, long currentTime) {
    metrics['duration'].collectedData.add(['value': currentTime - startTime])
    metrics['last_collection_time'].collectedData.add(['value': startTime])
}


void gatherMetrics(node, Map metrics) {
    name = node.displayName
    println('Gather data for ' + name)
    dataLabels = [
        'agent_name': name,
        'node_labels': node.labelString
    ]

    Closure addMetric = { name, value, extraLabel=[] ->
        labels = dataLabels.clone()
        labels.putAll(extraLabel)
        metrics[name].collectedData.add(['value': value, 'labels': labels])
    }

    trace('1')
    String ip = checkNode(node)
    computer = node.toComputer()
    addMetric('has_offline_message', computer.getOfflineCause() ? 1 : 0)

    if (ip) {
        dataLabels.ip = ip
        addMetric('is_connected', 1)
    } else {
        addMetric('is_connected', 0)
        return // no further metric could be gathered
    }

    trace('2')
    monitorData = computer.monitorData // https://javadoc.jenkins.io/hudson/node_monitors/package-summary.html
    trace('3')
    /* 
        Callable methods and properties:
            https://javadoc.jenkins.io/hudson/model/Node.html
            https://javadoc.jenkins.io/hudson/model/Computer.html

        Further potential metric gathering endpoints:
            - computer.doScriptText(req, resp) Run arbitrary Groovy script and return result as plain text.
            - getLoadStatistics()
    */

    trace('4')
    clock_diff   = monitorData['hudson.node_monitors.ClockMonitor']
    responseTime = monitorData['hudson.node_monitors.ResponseTimeMonitor']
    diskSpace    = monitorData['hudson.node_monitors.DiskSpaceMonitor']
    tmpSpace     = monitorData['hudson.node_monitors.TemporarySpaceMonitor']
    swapSpace    = monitorData['hudson.node_monitors.SwapSpaceMonitor']

    if (clock_diff) {
        addMetric('clock_diff', (long)(clock_diff.diff))
        trace('5')
    } else {
        println('No clock diff data available for node ' + name)
    }

    if (responseTime) {
        addMetric('response_time', (int)(responseTime.average))
        trace('6')
    } else {
        println('No response time data available for node ' + name)
    }

    if (swapSpace) {
        addMetric('mem_total',  (long)(swapSpace.totalPhysicalMemory))
        addMetric('mem_free',   (long)(swapSpace.availablePhysicalMemory))
        addMetric('swap_total', (long)(swapSpace.totalSwapSpace))
        addMetric('swap_free',  (long)(swapSpace.availableSwapSpace))
        trace('7')
    } else {
        println('No swapSpace/memorySpace data available for node ' + name)
    }

    if (diskSpace) {
        // Potential improvement: Multi agent nodes are aggregated in ipMap under the same?
        addMetric('jenkins_dir_free',  (long)(diskSpace.freeSize),           ['path': diskSpace.path])
        addMetric('jenkins_dir_total', Double.parseDouble(diskSpace.gbLeft), ['path': diskSpace.path])
        trace('8')
    } else {
        println('No diskSpace data available for node ' + name)
    }
    if (tmpSpace) {
        addMetric('tmp_dir_free', (long)(tmpSpace.freeSize))
        addMetric('tmp_dir_total', Double.parseDouble(tmpSpace.gbLeft))
        trace('9')
    } else {
        println('No tmpSpace data available for node ' + name)
    }

    addMetric('is_online',  computer.isOnline() ? 1 : 0 )
    trace('10')
    addMetric('executors_busy', (int)(computer.countBusy()))
    trace('11')
    addMetric('executors_total', (int)(node.numExecutors))
    trace('12')
}


String generatePrometheusPage(Map metrics) {
    metricsString = ''

    metrics.each { metricName, metricProps ->
        if (metricProps.collectedData.size() == 0) {
            return
        }
        refString = metricProps.hasProperty('ref') ? ' ref: ' + metricProps.ref : ''
        metricsString += '# HELP ' + metricName + ' ' + metricProps.help + refString + '\n'
        metricsString += '# TYPE ' + metricName + ' ' + metricProps.type + '\n'
        metricProps.collectedData.each { dataProps ->
            String labels = ''
            if (dataProps.labels) {
                labels += '{'
                labels += dataProps.labels.collect { k, v ->
                    k + '="' + v + '"'
                }.join(', ')
                labels += '}'
            }
            metricsString += metricProps.prefix + '_' + metricName + labels + ' ' + dataProps.value + '\n'
        }
    }
    return metricsString
}


void writeMetrics(String content) {
  fp = new FilePath(build.workspace, 'prometheus')

  if (fp != null) {
      fp.write(content, null)
  } else {
    throw Error('Failed to write out metrics file')
  }
}


main()

'' // Last "returned" Object is printed out after execution. I do not want that
