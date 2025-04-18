data "google_iam_role" "run_invoker" {
  name = "roles/run.invoker"
}

data google_project project {}

data "google_secret_manager_secret" "gtfs_data_secret" {
  secret_id = "gtfs-secrets-cta"
}

data "google_iam_role" "secret_viewer_role" {
  name = "roles/secretmanager.secretAccessor"
}

resource "google_project_iam_member" "allow_compute_service_user_cloud_run_invoker" {
  member  = "serviceAccount:${var.gen2_comnpute_user_email}"
  project = data.google_project.project.project_id
  role    = data.google_iam_role.run_invoker.id
}

resource "google_secret_manager_secret_iam_binding" "grant_view_secret_to_shared_compute" {
  members = [
    "serviceAccount:${var.gen2_comnpute_user_email}",
    "serviceAccount:${var.service_account_user_email}"
  ]
  role      = data.google_iam_role.secret_viewer_role.id
  secret_id = data.google_secret_manager_secret.gtfs_data_secret.id
}
