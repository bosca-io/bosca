{{/*
Compute PostgreSQL parameters scaled to available memory.
Expects .memoryGi (integer, GiB) and .maxConnections (string or int).

All math done in MB to avoid integer division truncation.
Uses div for ratios (div $memMB 4 = 25% of RAM).
*/}}
{{- define "bosca-common.pgAutoParams" -}}
{{- $memMB := mul (int .memoryGi) 1024 -}}
{{- $maxConn := int (default 200 .maxConnections) -}}
{{- $sharedBuffersMB := div $memMB 4 -}}
{{- $effectiveCacheMB := div (mul $memMB 3) 4 -}}
{{- $maintWorkMemMB := div $memMB 16 -}}
{{- $workMemKB := div (mul (div $memMB 4) 1024) $maxConn -}}
{{- $walBuffersMB := div $sharedBuffersMB 32 -}}
{{- $maxWalSizeGB := div $memMB 2048 -}}
shared_buffers: {{ printf "%dMB" $sharedBuffersMB | quote }}
effective_cache_size: {{ printf "%dMB" $effectiveCacheMB | quote }}
maintenance_work_mem: {{ printf "%dMB" $maintWorkMemMB | quote }}
work_mem: {{ printf "%dkB" $workMemKB | quote }}
wal_buffers: {{ printf "%dMB" $walBuffersMB | quote }}
max_wal_size: {{ printf "%dGB" (max 1 $maxWalSizeGB) | quote }}
min_wal_size: {{ printf "%dMB" (max 256 (mul (max 1 $maxWalSizeGB) 256)) | quote }}
{{- end }}
