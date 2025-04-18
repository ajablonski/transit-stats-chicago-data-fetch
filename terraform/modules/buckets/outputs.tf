output "gtfs_data_bucket_id" {
  value       = google_storage_bucket.gtfs_data.id
  description = "production bucket ID"
}
