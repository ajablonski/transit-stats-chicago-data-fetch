terraform {
  required_version = "1.1.7"
  required_providers {
    gcp = {
      source  = "hashicorp/google"
      version = "~> 4.16.0"
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
  project       = "transit-stats-chicago"
  storage_class = "STANDARD"
}

resource "google_storage_bucket" "terraform_state" {
  location = "US-CENTRAL1"
  name     = "tsc-terraform-state"
  project  = "transit-stats-chicago"

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

