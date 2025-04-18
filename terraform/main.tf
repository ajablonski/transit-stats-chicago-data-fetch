terraform {
  required_version = "~> 1.11"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6"
    }
  }
  backend "gcs" {
    bucket = "tsc-terraform-state"
    prefix = "data-fetch"
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

resource "google_project_iam_member" "allow_compute_service_user_cloud_run_invoker" {
  member  = "serviceAccount:${data.google_service_account.gen2_compute_user.email}"
  project = data.google_project.project.project_id
  role    = data.google_iam_role.run_invoker.id
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

module "production_bucket" {
  source = "./modules/buckets"
  service_account_email = google_service_account.gtfs_fetch_user.email
}

module "test_bucket" {
  source = "./modules/buckets"
  suffix = "-test"
  service_account_email = google_service_account.gtfs_fetch_user.email
}

module "triggers" {
  source = "./modules/triggers"
}

moved {
  from = google_pubsub_topic.static_scheduling_topic
  to   = module.triggers.google_pubsub_topic.static_scheduling_topic
}

moved {
  from = google_pubsub_topic.realtime_scheduling_topic
  to   = module.triggers.google_pubsub_topic.realtime_scheduling_topic
}

moved {
  from = google_cloud_scheduler_job.fetch_static_gtfs_data_trigger
  to = module.triggers.google_cloud_scheduler_job.fetch_static_gtfs_data_trigger
}


moved {
  from = google_cloud_scheduler_job.fetch_realtime_gtfs_data_trigger
  to = module.triggers.google_cloud_scheduler_job.fetch_realtime_gtfs_data_trigger
}