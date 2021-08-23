package com.waz.service.teams

import com.waz.content.UserPreferences
import com.waz.content.UserPreferences._
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.sync.handler.FeatureConfigsSyncHandler
import com.waz.log.LogSE._
import com.waz.model.{FeatureConfigEvent, FeatureConfigUpdateEvent, FileSharingFeatureConfig}
import com.waz.service.EventScheduler
import com.waz.service.EventScheduler.Stage
import com.waz.utils.JsonDecoder

import scala.concurrent.Future

trait FeatureConfigsService {
  def eventProcessingStage: Stage.Atomic
  def updateAppLock(): Future[Unit]
  def updateFileSharing(): Future[Unit]
}

class FeatureConfigsServiceImpl(syncHandler: FeatureConfigsSyncHandler,
                                userPrefs: UserPreferences)
  extends FeatureConfigsService with DerivedLogTag {
  import com.waz.threading.Threading.Implicits.Background

  override def eventProcessingStage: Stage.Atomic = EventScheduler.Stage[FeatureConfigEvent] { (_, events) =>
    Future.traverse(events)(processUpdateEvent)
  }

  private def processUpdateEvent(event: FeatureConfigEvent): Future[Unit] = event match {
    case FeatureConfigUpdateEvent("fileSharing", data) =>
      val fileSharing = JsonDecoder.decode[FileSharingFeatureConfig](data)
      verbose(l"File sharing enabled: ${fileSharing.isEnabled}")
      storeFileSharing(fileSharing)

    case _ =>
      Future.successful(())
  }

  override def updateAppLock(): Future[Unit] =
    for {
      appLock <- syncHandler.fetchAppLock()
      _       =  verbose(l"AppLock feature flag : $appLock")
      _       <- userPrefs(AppLockFeatureEnabled) := appLock.enabled
      _       <- userPrefs(AppLockForced)  := appLock.forced
      _       <- if (!appLock.enabled) userPrefs(AppLockEnabled) := false
                 else if (appLock.forced) userPrefs(AppLockEnabled) := true
                 else Future.successful(())
      _       <- userPrefs(AppLockTimeout) := appLock.timeout
    } yield ()

  override def updateFileSharing(): Future[Unit] =
    for {
      fileSharing <- syncHandler.fetchFileSharing()
      _           =  verbose(l"FileSharing feature config : $fileSharing")
      _           <- storeFileSharing(fileSharing)
    } yield ()

  private def storeFileSharing(fileSharing: FileSharingFeatureConfig): Future[Unit] = {
    for {
      existingValue <- userPrefs(FileSharingFeatureEnabled).apply()
      newValue      =  fileSharing.isEnabled
      _             <- userPrefs(FileSharingFeatureEnabled) := newValue
                       // Inform of changes.
      _             <- if (existingValue != newValue) userPrefs(ShouldInformFileSharingRestriction) := true
                       else Future.successful(())
    } yield ()
  }

}
