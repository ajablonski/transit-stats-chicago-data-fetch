terraform {
  required_version = "1.4.5"
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

provider "google-beta" {
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
    data       = base64encode(jsonencode({ "trigger" = "fetch-static-gtfs-data" }))
  }
}

resource "google_cloud_scheduler_job" "fetch_realtime_gtfs_data_trigger" {
  name      = "fetch-realtime-gtfs-data-trigger"
  schedule  = "* * * * *"
  time_zone = "America/Chicago"

  pubsub_target {
    topic_name = google_pubsub_topic.realtime_scheduling_topic.id
    data       = base64encode(jsonencode({ "trigger" = "fetch-realtime-gtfs-data" }))
  }
}

data "google_project" "project" {}

resource "google_project_service_identity" "cloudbuild_service_account" {
  provider = google-beta
  project  = data.google_project.project.project_id
  service  = "cloudbuild.googleapis.com"
}

data "google_iam_role" "cloudfunctions_developer_role" {
  name = "roles/cloudfunctions.developer"
}

resource "google_project_iam_member" "allow_cloudbuild_functions_access" {
  member  = "serviceAccount:${google_project_service_identity.cloudbuild_service_account.email}"
  project = data.google_project.project.project_id
  role    = data.google_iam_role.cloudfunctions_developer_role.id
}

data "google_iam_role" "secret_viewer_role" {
  name = "roles/secretmanager.secretAccessor"
}

data "google_secret_manager_secret" "gtfs_data_secret" {
  secret_id = "gtfs-secrets-cta"
}

resource "google_secret_manager_secret_iam_binding" "grant_view_secret_to_functions_user" {
  members   = ["serviceAccount:${data.google_service_account.appspot_service_account.email}"]
  role      = data.google_iam_role.secret_viewer_role.id
  secret_id = data.google_secret_manager_secret.gtfs_data_secret.id
}

data "google_service_account" "appspot_service_account" {
  account_id = "transit-stats-chicago@appspot.gserviceaccount.com"
}

data "google_service_account" "gen2_compute_user" {
  account_id = "998544061327-compute@developer.gserviceaccount.com"
}

data "google_iam_role" "service_account_role" {
  name = "roles/iam.serviceAccountUser"
}

data "google_iam_role" "role_viewer" {
  name = "roles/iam.roleViewer"
}

resource "google_project_iam_member" "allow_cloudbuild_role_viewer_access" {
  member  = "serviceAccount:${google_project_service_identity.cloudbuild_service_account.email}"
  project = data.google_project.project.project_id
  role    = data.google_iam_role.role_viewer.id
}

resource "google_service_account_iam_member" "allow_build_agent_as_cloud_functions_service_user" {
  member             = "serviceAccount:${google_project_service_identity.cloudbuild_service_account.email}"
  role               = data.google_iam_role.service_account_role.name
  service_account_id = data.google_service_account.appspot_service_account.id
}

resource "google_service_account_iam_member" "allow_build_agent_as_cloud_functions_v2_service_user" {
  member             = "serviceAccount:${google_project_service_identity.cloudbuild_service_account.email}"
  role               = data.google_iam_role.service_account_role.name
  service_account_id = data.google_service_account.gen2_compute_user.id
}

resource "google_cloudbuild_trigger" "cloudbuild_trigger" {
  name               = "fetch-gtfs-pipeline-build"
  include_build_logs = "INCLUDE_BUILD_LOGS_WITH_STATUS"
  filename           = "cloudbuild.yaml"

  github {
    name  = "transit-stats-chicago-data-fetch"
    owner = "ajablonski"
    push {
      branch = "^main$"
    }
  }

  service_account = ""
}
