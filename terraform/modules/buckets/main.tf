resource "google_storage_bucket" "gtfs_data" {
  location                    = "US-CENTRAL1"
  name                        = "tsc-gtfs-data${var.suffix}"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  requester_pays              = true
}

data "google_iam_role" "storage_object_user" {
  name = "roles/storage.objectUser"
}

resource "google_storage_bucket_iam_binding" "allow_gtfs_function_user_bucket_access" {
  role   = data.google_iam_role.storage_object_user.id
  bucket = google_storage_bucket.gtfs_data.id
  members = [
    "serviceAccount:${var.service_account_email}",
  ]
}

data "google_iam_role" "service_usage_consumer_role" {
  name = "roles/serviceusage.serviceUsageConsumer"
}

data "google_project" "project" {}

resource "google_project_iam_binding" "allow_gtfs_fetch_user_to_bill_bucket_requests_to_project" {
  # Allows the named user to specify this project as the billing project when requesting data against a requester-pays bucket
  members = [
    "serviceAccount:${var.service_account_email}"
  ]
  project = data.google_project.project.project_id
  role    = data.google_iam_role.service_usage_consumer_role.id
}