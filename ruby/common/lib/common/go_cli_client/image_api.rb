# Copyright 2015 VMware, Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, without warranties or
# conditions of any kind, EITHER EXPRESS OR IMPLIED. See the License for the
# specific language governing permissions and limitations under the License.

module EsxCloud
  class GoCliClient
    module ImageApi
      # @param [String] path
      # @param [String] name
      # @return [Image]
      def create_image(path, name = nil, image_replication = nil)
        if name.nil?
          name = random_name("image-")
        end

        cmd = "image create '#{path}' -n '#{name}'"
        if image_replication
          cmd += " -i '#{image_replication}'"
        end
        image_id = run_cli(cmd)

        find_image_by_id(image_id)
      end

      # @param [String] id
      # @param [Hash] payload
      # @return [Image]
      def create_image_from_vm(id, payload)
        @api_client.create_image_from_vm(id, payload)
      end

      # @param [String] id
      # @return [Image]
      def find_image_by_id(id)
        result = run_cli("image show #{id}")
        get_image_from_response(result)
      end

      # @return [ImageList]
      def find_all_images
        result = run_cli("image list")
        get_image_list_from_response(result)
      end

      # @param [String] id
      # @return [Boolean]
      def delete_image(id)
        run_cli("image delete '#{id}'")
        true
      end

      # @param [String] id
      # @param [String] state
      # @return [TaskList]
      def get_image_tasks(id, state = nil)
        @api_client.get_image_tasks(id, state)
      end

      private

      def get_image_from_response(result)
        values = result.split("\n", -1)
        image_attributes = values[0].split("\t", -1)
        image_hash = Hash.new
        image_hash["id"]                  = image_attributes[0] unless image_attributes[0] == ""
        image_hash["name"]                = image_attributes[1] unless image_attributes[1] == ""
        image_hash["state"]               = image_attributes[2] unless image_attributes[2] == ""
        image_hash["size"]                = image_attributes[3].to_i unless image_attributes[3] == ""
        image_hash["replicationType"]     = image_attributes[4] unless image_attributes[4] == ""
        image_hash["replicationProgress"] = image_attributes[5] unless image_attributes[5] == ""
        image_hash["seedingProgress"]     = image_attributes[6] unless image_attributes[6] == ""
        if image_attributes.size == 7
          image_hash["settings"]          = getSettings(values[2],"\t")
        else
          image_hash["settings"]          = getSettings(image_attributes[7], ":")
        end

        Image.create_from_hash(image_hash)
      end

      def get_image_list_from_response(result)
        images = Array.new
        result.split("\n").map do |line|
          image_info = line.split("\t")
          next unless image_info.size > 1
          images << get_image_details(image_info[0])
        end

        ImageList.new(images.compact)
      end

      def get_image_details(image_id)
        begin
          find_image_by_id image_id
        rescue EsxCloud::CliError => e
          raise() unless e.message.include? "ImageNotFound"
          nil
        end
      end

      def getSettings(settings, delim)
        settings_new = Array.new
        if settings != "" and settings != nil
          settings_new = settings.split(",").map do |setting|
            settingToHash(setting, delim)
          end
        end

        settings_new
      end


      def settingToHash(setting, delim)
        setting_attribs = setting.split(delim, -1)
        settings_hash = Hash.new
        settings_hash["name"] = setting_attribs[0] unless setting_attribs[0] == ""
        settings_hash["defaultValue"] = setting_attribs[1] unless setting_attribs[1] == ""

        settings_hash
      end
    end
  end
end
