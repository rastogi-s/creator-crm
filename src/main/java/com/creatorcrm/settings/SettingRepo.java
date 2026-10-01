package com.creatorcrm.settings;

import com.creatorcrm.settings.SettingsService.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepo extends JpaRepository<Setting, String> {}
