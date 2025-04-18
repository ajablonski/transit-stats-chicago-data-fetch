resource "google_pubsub_topic" "static_scheduling_topic" {
  name                       = "fetch-static-gtfs-data-triggers"
  message_retention_duration = "86400s"
}

resource "google_pubsub_topic" "realtime_scheduling_topic" {
  name                       = "fetch-realtime-gtfs-data-triggers"
  message_retention_duration = "86400s"
}

resource "google_cloud_scheduler_job" "fetch_static_gtfs_data_trigger" {
  name      = "fetch-static-gtfs-data-trigger"
  schedule  = "0 0 * * *"
  time_zone = "America/Chicago"

  pubsub_target {
    topic_name = google_pubsub_topic.static_scheduling_topic.id
    data = base64encode(jsonencode({ "trigger" = "fetch-static-gtfs-data" }))
  }
}

resource "google_cloud_scheduler_job" "fetch_realtime_gtfs_data_trigger" {
  name      = "fetch-realtime-gtfs-data-trigger"
  schedule  = "* * * * *"
  time_zone = "America/Chicago"

  pubsub_target {
    topic_name = google_pubsub_topic.realtime_scheduling_topic.id
    data = base64encode(jsonencode({ "trigger" = "fetch-realtime-gtfs-data" }))
  }
}
