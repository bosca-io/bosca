update profiles pr
set visibility = 'user'::profile_visibility,
    modified   = now()
where pr.visibility = 'public'::profile_visibility;

update profile_attributes
set visibility = 'user'::profile_visibility
where visibility = 'public'::profile_visibility;