Gem::Specification.new do |s|
  s.name          = 'logstash-filter-pubsub-postgresql-guardium'
  s.version       = '0.1.0'
  s.licenses      = ['Apache-2.0']
  s.summary       = 'This filter plugin parses GCP Pub/Sub events recevied from PostgreSQL'
  s.description   = 'This filter plugin parses GCP Pub/Sub events recevied from PostgreSQL and maps to GuardRecord'
  #s.homepage      = 'TODO: Put your plugin''s website or public repo URL here.'
  s.authors       = ['IBM']
  s.email         = ''
  s.require_paths = ['lib']

  # Files
  s.files = Dir['lib/**/*','spec/**/*','vendor/**/*','*.gemspec','*.md','CONTRIBUTORS','Gemfile','LICENSE','NOTICE.TXT']
   # Tests
  s.test_files = s.files.grep(%r{^(test|spec|features)/})

  # Special flag to let us know this is actually a logstash plugin
  s.metadata = { "logstash_plugin" => "true", "logstash_group" => "filter" }

  # Gem dependencies
  s.add_runtime_dependency "logstash-core-plugin-api", "~> 2.0"
  s.add_runtime_dependency "logstash-input-google_pubsub", "~> 1.2.1"
  s.add_development_dependency 'logstash-devutils'
end