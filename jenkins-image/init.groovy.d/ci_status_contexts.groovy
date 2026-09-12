import hudson.model.Cause
import jenkins.model.Jenkins

// This runs on every controller startup from a read-only bind mount. The settings belong to
// the controller, not to a Jenkinsfile: a GitHub API failure can happen before Jenkinsfile
// retrieval and before Pipeline code has a chance to report its own status.
final String githubSourceClass = 'org.jenkinsci.plugins.github_branch_source.GitHubSCMSource'
final String contextTraitClassName =
    'org.jenkinsci.plugins.githubScmTraitNotificationContext.NotificationContextTrait'
final String periodicTriggerClassName =
    'com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger'
final String retryScanInterval = '5m'

final Map<String, String> jobContexts = [
    'ssau-bot-backend'        : 'ci/backend',
    'ssau-bot-frontend'       : 'ci/frontend',
    'calendar-service-backend': 'ci/backend',
    'calendar-service-frontend': 'ci/frontend',
    'admin-console-backend'   : 'ci/backend',
    'admin-console-frontend'  : 'ci/frontend',
].asImmutable()

def jenkins = Jenkins.get()
def classLoader = jenkins.pluginManager.uberClassLoader

Class contextTraitClass
Class periodicTriggerClass
try {
  contextTraitClass = classLoader.loadClass(contextTraitClassName)
  periodicTriggerClass = classLoader.loadClass(periodicTriggerClassName)
} catch (ClassNotFoundException error) {
  println("[ci-status-contexts] Required plugin is unavailable: ${error.message}")
  return
}

jobContexts.each { String jobName, String contextLabel ->
  def job = jenkins.getItemByFullName(jobName)
  if (job == null || !job.metaClass.respondsTo(job, 'getSourcesList')) {
    println("[ci-status-contexts] Job '${jobName}' is absent or is not multibranch; skipping.")
    return
  }

  boolean changed = false
  def sources = job.sourcesList.data.findAll { branchSource ->
    branchSource.source?.class?.name == githubSourceClass
  }

  if (sources.empty) {
    println("[ci-status-contexts] Job '${jobName}' has no GitHub Branch Source; skipping.")
    return
  }

  sources.each { branchSource ->
    def traits = branchSource.source.traits
    def configuredTraits = traits.findAll { configuredTrait ->
      configuredTrait.class.name == contextTraitClassName
    }
    boolean expectedContext = configuredTraits.size() == 1 &&
      configuredTraits.first().contextLabel == contextLabel &&
      !configuredTraits.first().typeSuffix &&
      !configuredTraits.first().multipleStatuses

    if (!expectedContext) {
      traits.removeAll { configuredTrait -> configuredTrait.class.name == contextTraitClassName }
      traits.add(contextTraitClass
        .getConstructor(String.class, Boolean.TYPE)
        .newInstance(contextLabel, false))
      changed = true
    }
  }

  def periodicTriggers = job.triggers.values().findAll { trigger ->
    periodicTriggerClass.isInstance(trigger)
  }
  boolean expectedRetryTrigger = periodicTriggers.size() == 1 &&
    periodicTriggers.first().interval == retryScanInterval

  if (!expectedRetryTrigger) {
    periodicTriggers.each { trigger -> job.removeTrigger(trigger) }
    job.addTrigger(periodicTriggerClass.getConstructor(String.class).newInstance(retryScanInterval))
    changed = true
  }

  if (changed) {
    job.save()
    // A configuration change should not wait for the next webhook. The computed-folder scan is
    // also the retry path for a transient GitHub API 5xx before Pipeline startup.
    job.scheduleBuild(5, new Cause.RemoteCause('jenkins-init', 'Applied CI status configuration'))
    println("[ci-status-contexts] Configured '${jobName}' as '${contextLabel}', scan every ${retryScanInterval}.")
  }
}
