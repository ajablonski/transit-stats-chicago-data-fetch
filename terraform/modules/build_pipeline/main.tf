terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6"
    }
    google-beta = {
      source = "hashicorp/google-beta"
      version = "~> 6"
    }
  }
}

provider "google-beta" {
  project = "transit-stats-chicago"
  region  = "us-central1"
}


resource "google_project_service_identity" "cloudbuild_service_account" {
  provider = google-beta
  project  = var.project_id
  service  = "cloudbuild.googleapis.com"
}

data "google_iam_role" "cloudfunctions_developer_role" {
  name = "roles/cloudfunctions.developer"
}

resource "google_project_iam_member" "allow_cloudbuild_functions_access" {
  member  = "serviceAccount:${google_project_service_identity.cloudbuild_service_account.email}"
  project = var.project_id
  role    = data.google_iam_role.cloudfunctions_developer_role.id
}

data "google_iam_role" "service_account_role" {
  name = "roles/iam.serviceAccountUser"
}

data "google_iam_role" "role_viewer" {
  name = "roles/iam.roleViewer"
}

resource "google_project_iam_member" "allow_cloudbuild_role_viewer_access" {
  member  = "serviceAccount:${google_project_service_identity.cloudbuild_service_account.email}"
  project = var.project_id
  role    = data.google_iam_role.role_viewer.id
}

resource "google_service_account_iam_member" "allow_build_agent_to_use_service_user" {
  count = length(var.service_account_ids_used_by_build_agent)
  member             = "serviceAccount:${google_project_service_identity.cloudbuild_service_account.email}"
  role               = data.google_iam_role.service_account_role.name
  service_account_id = var.service_account_ids_used_by_build_agent[count.index]
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
