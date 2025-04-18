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

module "build_pipeline" {
  source     = "./modules/build_pipeline"
  service_account_ids_used_by_build_agent = [
    data.google_service_account.gen2_compute_user.id,
    google_service_account.gtfs_fetch_user.id
  ]
}

module "production_bucket" {
  source                = "./modules/buckets"
  service_account_email = google_service_account.gtfs_fetch_user.email
}

module "test_bucket" {
  source                = "./modules/buckets"
  suffix                = "-test"
  service_account_email = google_service_account.gtfs_fetch_user.email
}

module "function_permissions" {
  source                     = "./modules/function_permissions"
  gen2_comnpute_user_email   = data.google_service_account.gen2_compute_user.email
  service_account_user_email = google_service_account.gtfs_fetch_user.email
}

module "triggers" {
  source = "./modules/triggers"
}
