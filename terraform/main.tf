terraform {
  required_version = "1.2.4"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 4.27.0"
    }
  }
  backend "gcs" {
    bucket = "tsc-terraform-state"
  }
}

provider "google" {
  project = "transit-stats-chicago"
  region  = "us-central1"
}

resource "google_storage_bucket" "gtfs_data" {
  location      = "US-CENTRAL1"
  name          = "tsc-gtfs-data"
  storage_class = "STANDARD"
}

resource "google_storage_bucket" "terraform_state" {
  location = "US-CENTRAL1"
  name     = "tsc-terraform-state"

  lifecycle_rule {
    action {
      type = "Delete"
    }

    condition {
      age                        = 0
      days_since_custom_time     = 0
      days_since_noncurrent_time = 0
      matches_storage_class      = []
      num_newer_versions         = 2
      with_state                 = "ARCHIVED"
    }
  }

  lifecycle_rule {
    action {
      type = "Delete"
    }

    condition {
      age                        = 0
      days_since_custom_time     = 0
      days_since_noncurrent_time = 7
      matches_storage_class      = []
      num_newer_versions         = 0
      with_state                 = "ANY"
    }
  }

  versioning {
    enabled = true
  }
}

resource "google_storage_bucket" "artifacts" {
  location      = "US-CENTRAL1"
  name          = "tsc-artifacts"
  storage_class = "STANDARD"
}

data "google_storage_bucket_object" "fetch_gtfs_data_deployment" {
  bucket = google_storage_bucket.artifacts.name
  name   = "fetch-gtfs-data/app.zip"
}

resource "local_file" "dummy_template_file" {
  filename = "gtfs_data_hash.md5"
  content  = data.google_storage_bucket_object.fetch_gtfs_data_deployment.md5hash
}

resource "google_cloudfunctions_function" "fetch_gtfs_data" {
  depends_on = [
    local_file.dummy_template_file,
    data.google_storage_bucket_object.fetch_gtfs_data_deployment
  ]
  name                  = "fetch-gtfs-data"
  runtime               = "java17"
  source_archive_bucket = google_storage_bucket.artifacts.name
  source_archive_object = data.google_storage_bucket_object.fetch_gtfs_data_deployment.name
  ingress_settings      = "ALLOW_INTERNAL_ONLY"
  entry_point           = "com.github.ajablonski.FetchStaticGtfsData"
  max_instances         = 1
  event_trigger {
    event_type = "google.pubsub.topic.publish"
    resource   = google_pubsub_topic.scheduling_topic.id
  }
}

resource "google_pubsub_topic" "scheduling_topic" {
  name                       = "fetch-gtfs-data-triggers"
  message_retention_duration = "86400s"
}

resource "google_cloud_scheduler_job" "fetch_gtfs_data_trigger" {
  name      = "fetch-gtfs-data-trigger"
  schedule  = "0 0 * * *"
  time_zone = "America/Chicago"

  pubsub_target {
    topic_name = google_pubsub_topic.scheduling_topic.id
    data       = base64encode(jsonencode({ "trigger" = "fetch-gtfs-data" }))
  }
}

resource "google_cloudbuild_trigger" "cloudbuild_trigger" {
  name               = "fetch-gtfs-pipeline-build"
  include_build_logs = "INCLUDE_BUILD_LOGS_WITH_STATUS"
  filename           = "cloudbuild.yaml"

  github {
    name  = "transit-stats-chicago-gcp"
    owner = "ajablonski"
    push {
      branch = "^main$"
    }
  }
}
