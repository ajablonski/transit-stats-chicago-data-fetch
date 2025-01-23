terraform {
  required_version = "~> 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 4.27.0"
    }
  }
  backend "gcs" {
    bucket = "tsc-terraform-state"
    prefix = "vehicles-data-fetch"
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
  location                    = "US-CENTRAL1"
  name                        = "tsc-gtfs-data"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
}

resource "google_storage_bucket" "gtfs_data_test" {
  location                    = "US-CENTRAL1"
  name                        = "tsc-gtfs-data-test"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  requester_pays              = true
}

resource "google_storage_bucket" "terraform_state" {
  location                    = "US-CENTRAL1"
  name                        = "tsc-terraform-state"
  uniform_bucket_level_access = true

  lifecycle_rule {
    action {
      type = "Delete"
    }

    condition {
      age                        = 0
      days_since_custom_time     = 0
      days_since_noncurrent_time = 0
      matches_storage_class = []
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
      matches_storage_class = []
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

data "google_project" "project" {}

# Used for Gen2 Cloud Functions
# Also used as identity for invoking cloud functions from Pub/sub subscription
data "google_service_account" "gen2_compute_user" {
  account_id = "${data.google_project.project.number}-compute@developer.gserviceaccount.com"
}

resource "google_service_account" "gtfs_fetch_user" {
  account_id   = "gtfs-fetch"
  display_name = "GTFS Fetch Service User"
}

data "google_iam_role" "run_invoker" {
  name = "roles/run.invoker"
}

data "google_iam_role" "storage_object_user" {
  name = "roles/storage.objectUser"
}

resource "google_project_iam_member" "allow_compute_service_user_cloud_run_invoker" {
  member  = "serviceAccount:${data.google_service_account.gen2_compute_user.email}"
  project = data.google_project.project.project_id
  role    = data.google_iam_role.run_invoker.id
}

resource "google_storage_bucket_iam_binding" "allow_gtfs_function_user_bucket_access" {
  role   = data.google_iam_role.storage_object_user.id
  bucket = google_storage_bucket.gtfs_data.id
  members = [
    "serviceAccount:${google_service_account.gtfs_fetch_user.email}",
    "serviceAccount:${data.google_service_account.gen2_compute_user.email}"
  ]
}

resource "google_storage_bucket_iam_binding" "allow_gtfs_function_user_bucket_test_access" {
  role   = data.google_iam_role.storage_object_user.id
  bucket = google_storage_bucket.gtfs_data_test.id
  members = [
    "serviceAccount:${google_service_account.gtfs_fetch_user.email}"
  ]
}

data "google_iam_role" "secret_viewer_role" {
  name = "roles/secretmanager.secretAccessor"
}

data "google_secret_manager_secret" "gtfs_data_secret" {
  secret_id = "gtfs-secrets-cta"
}

resource "google_secret_manager_secret_iam_binding" "grant_view_secret_to_shared_compute" {
  members = [
    "serviceAccount:${data.google_service_account.gen2_compute_user.email}",
    "serviceAccount:${google_service_account.gtfs_fetch_user.email}"
  ]
  role      = data.google_iam_role.secret_viewer_role.id
  secret_id = data.google_secret_manager_secret.gtfs_data_secret.id
}

module "build_pipeline" {
  source     = "./modules/build_pipeline"
  project_id = data.google_project.project.project_id
  service_account_ids_used_by_build_agent = [
    data.google_service_account.gen2_compute_user.id,
    google_service_account.gtfs_fetch_user.id
  ]
}

data "google_iam_role" "service_usage_consumer_role" {
  name = "roles/serviceusage.serviceUsageConsumer"
}

resource "google_project_iam_binding" "allow_gtfs_fetch_user_to_bill_bucket_requests_to_project" {
  # Allows the named user to specify this project as the billing project when requesting data against a requester-pays bucket
  members = [
    "serviceAccount:${google_service_account.gtfs_fetch_user.email}"
  ]
  project = data.google_project.project.project_id
  role    = data.google_iam_role.service_usage_consumer_role.id
}